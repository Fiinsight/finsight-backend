package com.finsight.learning;

import com.finsight.auth.UserProfileService;
import com.finsight.auth.UserRepository;
import com.finsight.briefing.BriefingService;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.external.AiServiceClient;
import com.finsight.external.AiRewriteRequest;
import com.finsight.term.Term;
import com.finsight.term.TermRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningService {
    public record Glossary(String term, String definition) {}
    public record DailyTerm(String term, String definition, Long newsId, String title, String reason) {}
    public record Question(String term, String prompt, List<String> options, QuestionType type) {}
    public record Lesson(Long newsId, String level, String summary, String readingGuide, String mode,
                         List<Glossary> glossary, Question question) {}
    record GradedQuestion(Question publicQuestion, String level, String correctAnswer) {}
    public record AnswerResult(boolean correct, String definition, String message, String termDefinition) {}
    public record Review(Long newsId, String title, String term, String definition, String level, Instant answeredAt) {}
    private static final List<Set<String>> TERM_FIELDS = List.of(
            Set.of("금리", "기준금리", "국채금리", "환율", "원화", "원달러", "물가", "인플레이션", "양적완화"),
            Set.of("매출", "영업이익", "실적", "실적 전망", "수출", "수입", "설비투자", "재고 조정"),
            Set.of("PER", "PBR", "배당수익률", "시가총액", "밸류에이션"),
            Set.of("코스피", "코스닥", "수급", "외국인 순매수", "매수/매도", "변동성", "서킷브레이커", "투자"),
            Set.of("공시", "유상증자", "공모주", "수요예측"),
            Set.of("AI", "반도체", "HBM"),
            Set.of("PF", "프로젝트파이낸싱", "연체율"));
    private final NewsRepository news;
    private final UserRepository users;
    private final TermRepository terms;
    private final LearningProgressRepository progress;
    private final UserProfileService profiles;
    private final AiServiceClient ai;
    private final ArticleContentExtractor extractor;
    private final TermMasteryService mastery;
    private final BriefingService briefing;
    public LearningService(NewsRepository news, UserRepository users, TermRepository terms,
                           LearningProgressRepository progress, UserProfileService profiles,
                           AiServiceClient ai, ArticleContentExtractor extractor, TermMasteryService mastery, BriefingService briefing) {
        this.news = news; this.users = users; this.terms = terms; this.progress = progress;
        this.profiles = profiles; this.ai = ai; this.extractor = extractor; this.mastery = mastery;
        this.briefing = briefing;
    }
    @Transactional(readOnly = true)
    public Lesson lesson(Long userId, Long newsId, String requestedLevel) {
        return lesson(userId, newsId, requestedLevel, null);
    }
    @Transactional(readOnly = true)
    public Lesson lesson(Long userId, Long newsId, String requestedLevel, String requestedTerm) {
        String level = requestedLevel == null ? profiles.getOnboarding(userId).learningLevel() : requestedLevel;
        if (level == null) level = "beginner";
        validateLevel(level);
        News article = article(newsId);
        String body = extractor.clean(article.getTitle(), article.getRawContent());
        String stored = switch (level) {
            case "normal" -> article.getRewrittenNormal();
            case "analyst" -> article.getRewrittenAnalyst();
            default -> article.getRewrittenBeginner();
        };
        stored = ArticleContentExtractor.stripPhotoNoise(stored);
        String mode = "REWRITTEN".equals(article.getRewriteMode()) ? "REWRITTEN" : "EXCERPT";
        boolean usableStored = article.getRewriteMode() != null && stored != null && !stored.isBlank();
        var aid = usableStored || body == null || body.isBlank() ? Optional.<AiServiceClient.ReadingAid>empty()
                : ai.learning(new AiRewriteRequest(article.getTitle(), body, level));
        List<Term> matchedTerms = articleTerms(article);
        List<Glossary> glossary = matchedTerms.stream().limit(3)
                .map(t -> new Glossary(t.getTerm(), t.getShortDefinition())).toList();
        Map<String,TermMasteryService.Mastery> knowledge = new HashMap<>();
        mastery.list(userId).forEach(row -> knowledge.put(row.term(),row));
        Map<String,Double> probabilities = new HashMap<>();
        knowledge.forEach((term,row) -> probabilities.put(term,row.effectivePKnown()));
        String quizLevel = level;
        String selected = requestedTerm != null ? requestedTerm : KnowledgeTracing.select(matchedTerms.stream().filter(term -> question(article,term,quizLevel,needsConcept(knowledge.get(term.getTerm()))) != null).map(Term::getTerm).toList(), probabilities);
        Term first = matchedTerms.stream().filter(term -> term.getTerm().equals(selected)).findFirst().orElse(null);
        GradedQuestion quiz = first == null ? null : question(article, first, level, needsConcept(knowledge.get(first.getTerm())));
        if (requestedTerm != null && quiz == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 기사의 퀴즈 개념이 아닙니다.");
        return new Lesson(newsId, level,
                usableStored ? stored : aid.map(AiServiceClient.ReadingAid::summary).orElse("수준별 설명을 확인할 수 없습니다 (fallback). 원문 링크를 확인하세요."),
                aid.map(AiServiceClient.ReadingAid::readingGuide).orElse("아래 용어는 일반 개념 설명이며 기사의 사실과 구분해 읽어주세요."),
                usableStored ? mode : aid.isPresent() ? "EXCERPT" : "UNAVAILABLE", glossary,
                quiz == null ? null : quiz.publicQuestion());
    }
    @Transactional
    public AnswerResult answer(Long userId, Long newsId, String level, String term, int answerIndex) {
        validateLevel(level);
        News article = article(newsId);
        Term target = articleTerms(article).stream().filter(t -> t.getTerm().equals(term)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 기사의 퀴즈 문항이 아닙니다."));
        var known = mastery.list(userId).stream().filter(row -> row.term().equals(term)).findFirst().orElse(null);
        GradedQuestion graded = question(article, target, level, needsConcept(known));
        Question question = graded == null ? null : graded.publicQuestion();
        if (question == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "준비된 퀴즈가 없습니다.");
        if (answerIndex < 0 || answerIndex >= question.options().size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 답변입니다.");
        boolean correct = question.options().get(answerIndex).equals(graded.correctAnswer());
        LearningProgress record = progress.findByUser_IdAndNews_IdAndTermAndQuestionType(userId, newsId, term, question.type())
                .orElseGet(() -> new LearningProgress(users.findById(userId).orElseThrow(), article, term));
        record.answer(graded.level(), correct, question.type());
        progress.save(record);
        if (question.type() == QuestionType.DEFINITION) mastery.answer(userId, term, graded.level(), correct);
        return new AnswerResult(correct, graded.correctAnswer(), correct ? "정답이에요!" : "아쉬워요", target.getShortDefinition());
    }
    @Transactional(readOnly = true)
    public List<DailyTerm> dailyTerms(Long userId) {
        Map<String,TermMasteryService.Mastery> known = new HashMap<>();
        mastery.list(userId).forEach(row -> known.put(row.term(), row));
        LinkedHashMap<String,DailyTerm> candidates = new LinkedHashMap<>();
        for (var item : briefing.getTodayBriefing()) news.findById(item.id()).ifPresent(article -> addDailyTerms(candidates, article, known));
        if (candidates.size() < 3) {
            for (News article : news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(Instant.now().minus(java.time.Duration.ofHours(24)))) {
                addDailyTerms(candidates, article, known);
            }
        }
        return candidates.values().stream().sorted(Comparator
                .comparingInt((DailyTerm term) -> dailyPriority(known.get(term.term())))
                .thenComparingDouble(term -> known.containsKey(term.term()) ? known.get(term.term()).effectivePKnown() : KnowledgeTracing.P_INIT)
                .thenComparing(DailyTerm::term)).limit(3).toList();
    }

    private void addDailyTerms(Map<String,DailyTerm> candidates, News article, Map<String,TermMasteryService.Mastery> known) {
        if (article.getRawContent() == null || article.getRawContent().isBlank()
                || Objects.toString(article.getSource(), "").startsWith("SYNTHETIC")) return;
        for (Term term : articleTerms(article)) {
            if (term.getKind() != Term.Kind.CONCEPT) continue;
            var row = known.get(term.getTerm());
            if (row != null && TermMasteryService.wellKnown(row)) continue;
            String reason = row == null ? "처음 보는 개념" : Boolean.FALSE.equals(row.lastCorrect()) ? "지난번에 틀린 개념" : "익히는 중인 개념";
            candidates.putIfAbsent(term.getTerm(), new DailyTerm(term.getTerm(), term.getShortDefinition(), article.getId(), article.getTitle(), reason));
        }
    }

    private static int dailyPriority(TermMasteryService.Mastery row) {
        if (row == null) return 1;
        return TermMasteryService.needsReview(row.effectivePKnown(), row.lastCorrect()) ? 0 : 2;
    }
    @Transactional(readOnly = true)
    public List<Review> reviews(Long userId) {
        Map<String,Double> probabilities = new HashMap<>();
        mastery.list(userId).forEach(row -> probabilities.put(row.term(),row.effectivePKnown()));
        LinkedHashMap<String,LearningProgress> latest = new LinkedHashMap<>();
        progress.findByUser_IdOrderByAnsweredAtDesc(userId).stream().filter(row -> row.getQuestionType() == QuestionType.DEFINITION).forEach(row -> latest.putIfAbsent(row.getTerm(),row));
        Map<String,Term> dictionary = new HashMap<>();
        terms.findAll().forEach(term -> dictionary.put(term.getTerm(), term));
        return latest.values().stream()
                .filter(row -> row.getQuestionType() == QuestionType.DEFINITION)
                .filter(row -> dictionary.containsKey(row.getTerm()) && dictionary.get(row.getTerm()).quizEligible(row.getLevel()))
                .filter(row -> TermMasteryService.needsReview(probabilities.getOrDefault(row.getTerm(), KnowledgeTracing.P_INIT), row.isCorrect()))
                .sorted(Comparator
                .comparingDouble((LearningProgress row) -> probabilities.getOrDefault(row.getTerm(), KnowledgeTracing.P_INIT))
                .thenComparing(LearningProgress::getTerm)).limit(10)
                .map(p -> new Review(p.getNews().getId(), p.getNews().getTitle(), p.getTerm(),
                        terms.findByTermIgnoreCase(p.getTerm()).map(Term::getShortDefinition).orElse("정의를 확인할 수 없습니다."),
                        p.getLevel(), p.getAnsweredAt())).toList();
    }
    private News article(Long id) { return news.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "뉴스를 찾을 수 없습니다.")); }
    @Transactional(readOnly = true)
    public Glossary feedbackConcept(Long userId, News article, String level) {
        Map<String,Double> known = new HashMap<>();
        mastery.list(userId).forEach(row -> known.put(row.term(),row.effectivePKnown()));
        return articleTerms(article).stream().filter(term -> term.getKind() == Term.Kind.CONCEPT && question(article,term,level) != null)
                .min(Comparator.comparingDouble((Term term) -> known.getOrDefault(term.getTerm(),KnowledgeTracing.P_INIT)).thenComparing(Term::getTerm))
                .map(term -> new Glossary(term.getTerm(),term.getShortDefinition())).orElse(null);
    }
    List<Term> articleTerms(News article) {
        String content = article.getTitle() + " " + Objects.toString(article.getRawContent(), "");
        return terms.findAll().stream().filter(t -> content.contains(t.getTerm()) && t.getShortDefinition() != null && !t.getShortDefinition().isBlank())
                .sorted(Comparator.comparingInt((Term t) -> article.getTitle().contains(t.getTerm()) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt((Term t) -> t.getTerm().length()).reversed())
                        .thenComparing(Term::getTerm)).toList();
    }
    static boolean needsConcept(TermMasteryService.Mastery row) {
        return row == null || row.effectivePKnown() < 0.85 || row.attempts() < TermMasteryService.WELL_KNOWN_MIN_ATTEMPTS
                || Boolean.FALSE.equals(row.lastCorrect());
    }
    GradedQuestion question(News article, Term term, String level) {
        return question(article,term,level,false);
    }
    GradedQuestion question(News article, Term term, String level, boolean conceptFirst) {
        if (!term.quizEligible(level)) return null;
        if (conceptFirst) {
            GradedQuestion definition = beginnerQuestion(article, term, level);
            if (definition != null) return definition;
        }
        String body = Objects.toString(extractor.clean(article.getTitle(), article.getRawContent()), "");
        List<String> sentences = Arrays.stream(body.split("(?<=[.!?。])\\s+|\\n+"))
                .map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        Random seed = new Random(article.getId() ^ term.getTerm().hashCode());
        if ("normal".equals(level)) {
            List<Term> matched = articleTerms(article);
            for (String sentence : sentences) {
                if (sentence.length()>180 || !sentence.contains(term.getTerm()) || !sentence.matches(".*[0-9].*")) continue;
                if (matched.stream().anyMatch(other -> !other.getTerm().equals(term.getTerm())
                        && !term.getTerm().contains(other.getTerm()) && sentence.contains(other.getTerm()))) continue;
                // Mixed directions or forecast-only statements cannot be graded as observed changes.
                if (sentence.matches(".*(전망|예상|가능성|예측).*")) continue;
                boolean up = sentence.matches(".*(증가|상승|늘었|늘어|올랐|올라|인상).*"),
                        down = sentence.matches(".*(감소|하락|줄었|줄어|내렸|내려|인하).*"),
                        flat = sentence.matches(".*(동결|변화 없|유지|보합).*" );
                if ((up?1:0)+(down?1:0)+(flat?1:0) != 1) continue;
                List<String> options = new ArrayList<>(List.of("증가", "감소", "변화 없음"));
                Collections.shuffle(options, seed);
                return new GradedQuestion(new Question(term.getTerm(), "기사에서 ‘" + term.getTerm() + "’가 어떻게 변했나요?\n" + sentence, List.copyOf(options), QuestionType.DIRECTION), "normal", up?"증가":down?"감소":"변화 없음");
            }
        }
        if ("analyst".equals(level)) {
            for (String fact : sentences) {
                if (fact.length() > 180 || !fact.contains(term.getTerm()) || fact.matches(".*(전망|예상|가능성|예측).*")) continue;
                List<String> changed = factVariants(fact).stream().filter(value -> !body.contains(value)).distinct().limit(2).toList();
                if (changed.size() < 2) continue;
                List<String> options = new ArrayList<>(List.of(fact, changed.get(0), changed.get(1)));
                Collections.shuffle(options, seed);
                return new GradedQuestion(new Question(term.getTerm(), "다음 중 기사 원문과 일치하는 문장은 무엇인가요?",
                        List.copyOf(options), QuestionType.FACT_CHECK), level, fact);
            }
        }
        return beginnerQuestion(article, term, level);
    }

    static List<String> factVariants(String fact) {
        List<String> variants = new ArrayList<>();
        // Match only explicit amount/rate/count units; calendar and clock numbers stay intact.
        var number = java.util.regex.Pattern.compile("(?<![0-9.,])([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(?=\\s*(?:%|퍼센트|bp|베이시스포인트|조\\s*원|억\\s*원|만\\s*원|천\\s*원|원|달러|유로|엔|위안|개|명|대|건|톤|배럴))").matcher(fact);
        if (number.find()) {
            var value = new java.math.BigDecimal(number.group(1).replace(",", ""));
            for (int offset : List.of(1, 2)) variants.add(fact.substring(0, number.start(1))
                    + value.add(java.math.BigDecimal.valueOf(offset)).toPlainString() + fact.substring(number.end(1)));
        }
        for (var pair : List.of(List.of("상승", "하락"), List.of("증가", "감소"), List.of("인상", "인하"), List.of("늘었", "줄었"))) {
            for (int i = 0; i < 2; i++) if (fact.contains(pair.get(i)))
                variants.add(fact.replaceFirst(pair.get(i), pair.get(1-i)));
        }
        return variants;
    }
    private GradedQuestion beginnerQuestion(News article, Term term, String level) {
        Set<String> field = TERM_FIELDS.stream().filter(group -> group.contains(term.getTerm())).findFirst().orElse(Set.of());
        List<String> distractors = new ArrayList<>(terms.findAll().stream()
                .filter(t -> t.quizEligible("beginner") && field.contains(t.getTerm()) && !t.getTerm().equals(term.getTerm()))
                .sorted(Comparator.comparing(Term::getTerm)).map(Term::getShortDefinition)
                .filter(d -> d != null && !d.isBlank() && !d.equals(term.getShortDefinition())).distinct().toList());
        if (distractors.size() < 2) return null; // No unrelated definitions disguised as suitable distractors.
        Random seed = new Random(article.getId() ^ term.getTerm().hashCode());
        Collections.shuffle(distractors, seed);
        List<String> options = new ArrayList<>(List.of(term.getShortDefinition(), distractors.get(0), distractors.get(1)));
        Collections.shuffle(options, seed);
        return new GradedQuestion(new Question(term.getTerm(), "이 기사에 등장하는 ‘" + term.getTerm() + "’의 기본 의미는 무엇인가요?", List.copyOf(options), QuestionType.DEFINITION), level, term.getShortDefinition());
    }
    private void validateLevel(String level) {
        if (!List.of("beginner", "normal", "analyst").contains(level)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 읽기 수준입니다.");
    }
}
