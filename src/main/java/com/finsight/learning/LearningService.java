package com.finsight.learning;

import com.finsight.auth.UserProfileService;
import com.finsight.auth.UserRepository;
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
    public record Question(String term, String prompt, List<String> options) {}
    public record Lesson(Long newsId, String level, String summary, String readingGuide, String mode,
                         List<Glossary> glossary, Question question) {}
    public record AnswerResult(boolean correct, String definition, String message, int correctIndex) {}
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
    public LearningService(NewsRepository news, UserRepository users, TermRepository terms,
                           LearningProgressRepository progress, UserProfileService profiles,
                           AiServiceClient ai, ArticleContentExtractor extractor) {
        this.news = news; this.users = users; this.terms = terms; this.progress = progress;
        this.profiles = profiles; this.ai = ai; this.extractor = extractor;
    }
    @Transactional(readOnly = true)
    public Lesson lesson(Long userId, Long newsId, String requestedLevel) {
        String level = requestedLevel == null ? profiles.getOnboarding(userId).learningLevel() : requestedLevel;
        if (level == null) level = "beginner";
        validateLevel(level);
        News article = article(newsId);
        String body = extractor.clean(article.getTitle(), article.getRawContent());
        var aid = body == null || body.isBlank() ? Optional.<AiServiceClient.ReadingAid>empty()
                : ai.learning(new AiRewriteRequest(article.getTitle(), body, level));
        List<Term> matchedTerms = articleTerms(article);
        List<Glossary> glossary = matchedTerms.stream().limit(3)
                .map(t -> new Glossary(t.getTerm(), t.getShortDefinition())).toList();
        Term first = matchedTerms.stream().findFirst().orElse(null);
        return new Lesson(newsId, level,
                aid.map(AiServiceClient.ReadingAid::summary).orElse("수준별 설명을 확인할 수 없습니다 (fallback). 원문 링크를 확인하세요."),
                aid.map(AiServiceClient.ReadingAid::readingGuide).orElse("아래 용어는 일반 개념 설명이며 기사의 사실과 구분해 읽어주세요."),
                aid.map(AiServiceClient.ReadingAid::mode).orElse("UNAVAILABLE"), glossary,
                first == null ? null : question(article, first));
    }
    @Transactional
    public AnswerResult answer(Long userId, Long newsId, String level, String term, int answerIndex) {
        validateLevel(level);
        News article = article(newsId);
        Term target = articleTerms(article).stream().findFirst().filter(t -> t.getTerm().equals(term))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 기사의 퀴즈 문항이 아닙니다."));
        Question question = question(article, target);
        if (question == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "준비된 퀴즈가 없습니다.");
        if (answerIndex < 0 || answerIndex >= question.options().size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 답변입니다.");
        boolean correct = question.options().get(answerIndex).equals(target.getShortDefinition());
        LearningProgress record = progress.findByUser_IdAndNews_IdAndTerm(userId, newsId, term)
                .orElseGet(() -> new LearningProgress(users.findById(userId).orElseThrow(), article, term));
        record.answer(level, correct);
        progress.save(record);
        return new AnswerResult(correct, target.getShortDefinition(), correct ? "이 개념을 이해했어요. 복습 목록에서 제외됩니다." : "이 개념을 학습 화면의 복습 목록에 저장했어요.", question.options().indexOf(target.getShortDefinition()));
    }
    @Transactional(readOnly = true)
    public List<Review> reviews(Long userId) {
        return progress.findTop50ByUser_IdAndCorrectFalseOrderByAnsweredAtDesc(userId).stream()
                .map(p -> new Review(p.getNews().getId(), p.getNews().getTitle(), p.getTerm(),
                        terms.findByTermIgnoreCase(p.getTerm()).map(Term::getShortDefinition).orElse("정의를 확인할 수 없습니다."),
                        p.getLevel(), p.getAnsweredAt())).toList();
    }
    private News article(Long id) { return news.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "뉴스를 찾을 수 없습니다.")); }
    private List<Term> articleTerms(News article) {
        String content = article.getTitle() + " " + Objects.toString(article.getRawContent(), "");
        return terms.findAll().stream().filter(t -> content.contains(t.getTerm()) && t.getShortDefinition() != null && !t.getShortDefinition().isBlank())
                .sorted(Comparator.comparingInt((Term t) -> article.getTitle().contains(t.getTerm()) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt((Term t) -> t.getTerm().length()).reversed())
                        .thenComparing(Term::getTerm)).toList();
    }
    private Question question(News article, Term term) {
        Set<String> field = TERM_FIELDS.stream().filter(group -> group.contains(term.getTerm())).findFirst().orElse(Set.of());
        List<String> distractors = new ArrayList<>(terms.findAll().stream()
                .filter(t -> field.contains(t.getTerm()) && !t.getTerm().equals(term.getTerm()))
                .sorted(Comparator.comparing(Term::getTerm)).map(Term::getShortDefinition)
                .filter(d -> d != null && !d.isBlank() && !d.equals(term.getShortDefinition())).distinct().toList());
        if (distractors.size() < 2) return null; // No unrelated definitions disguised as suitable distractors.
        Random seed = new Random(article.getId() ^ term.getTerm().hashCode());
        Collections.shuffle(distractors, seed);
        List<String> options = new ArrayList<>(List.of(term.getShortDefinition(), distractors.get(0), distractors.get(1)));
        Collections.shuffle(options, seed);
        return new Question(term.getTerm(), "이 기사에 등장하는 ‘" + term.getTerm() + "’의 기본 의미는 무엇인가요?", List.copyOf(options));
    }
    private void validateLevel(String level) {
        if (!List.of("beginner", "normal", "analyst").contains(level)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 읽기 수준입니다.");
    }
}
