package com.finsight.judgement;

import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import com.finsight.auth.User;
import java.util.List;
import com.finsight.history.RecordDateRange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JudgementService {

    private static final String ACK_FEEDBACK_TEXT =
            "판단이 기록되었습니다. 다음 거래일 장 마감 후 결과를 확인해보세요.";

    private final JudgementRepository judgementRepository;
    private final NewsRepository newsRepository;

    public JudgementService(JudgementRepository judgementRepository, NewsRepository newsRepository) {
        this.judgementRepository = judgementRepository;
        this.newsRepository = newsRepository;
    }

    @org.springframework.transaction.annotation.Transactional
    public JudgementFeedbackResponse recordJudgement(User user, JudgementRequest request) {
        News news = newsRepository.findById(request.newsId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "존재하지 않는 뉴스 id 입니다: " + request.newsId()));
        var target = JudgementTargetResolver.resolve(news);
        if (target.symbol() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 기사는 판단할 대상이 없어요");

        // Lock the news row until commit; prevents concurrent inserts before the deferred unique constraint.
        newsRepository.lockForJudgement(news.getId());
        var existing = judgementRepository.findFirstByUserAndNewsIdOrderByCreatedAtDescIdDesc(user, news.getId());
        if (existing.isPresent()) {
            var judgement = existing.get();
            if (judgement.getActualDirection() != null || judgement.getFeedbackGeneratedAt() != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 결과가 나온 판단이에요");
            judgement.revise(request.choice(), request.reason());
            judgement.setTargetSymbol(target.symbol());
            judgementRepository.save(judgement);
            return acknowledgment(judgement, "판단을 수정했어요");
        }
        Judgement judgement = new Judgement(user, news, request.choice(), request.reason());
        judgement.setTargetSymbol(target.symbol());
        judgementRepository.save(judgement);

        return acknowledgment(judgement, ACK_FEEDBACK_TEXT);
    }

    static java.time.LocalDate expectedResultDate(java.time.Instant judgedAt) {
        try { return KoreanTradingCalendar.next(judgedAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()); }
        catch (IllegalArgumentException unsupportedCalendar) { return null; }
    }
    static java.time.Instant expectedResultAt(java.time.Instant judgedAt) {
        var date=expectedResultDate(judgedAt);
        return date==null?null:date.atTime(15,40).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }
    private JudgementFeedbackResponse acknowledgment(Judgement judgement, String message) {
        return new JudgementFeedbackResponse(judgement.getNews().getId(),judgement.getChoice(),message,
            judgement.getId(),expectedResultDate(judgement.getCreatedAt()),expectedResultAt(judgement.getCreatedAt()));
    }

    private JudgementHistoryResponse history(Judgement j) {
        var content = j.getFeedbackContent();
        var symbol = j.getTargetSymbol() == null ? j.getNews().getRelatedSymbol() : j.getTargetSymbol();
        String issue = null;
        String notice = null;
        if (java.util.Set.of("UP","DOWN","NEUTRAL").contains(java.util.Objects.toString(j.getActualDirection(),""))) {
            if (j.getTargetSymbol() == null && symbol != null && !symbol.equals(JudgementTargetResolver.resolve(j.getNews()).symbol())) {
                issue = "TARGET_MISMATCH";
                notice = "이 기사와 " + symbolName(symbol) + " 주가의 연결을 다시 확인해야 해요. 저장된 등락을 이 기사의 영향으로 설명할 수 없어 이전 적중 여부와 피드백을 표시하지 않아요.";
            } else if (j.getComparisonDate() == null) {
                issue = "LEGACY_COMPARISON";
                notice = "이 기록은 판단한 날의 가격으로 비교돼 다음 거래일 예측의 결과로 해석하기 어려워요. 대상과 비교 종가가 확인된 새 판단에서 설명을 확인해보세요.";
            }
        }
        if (issue != null) content = null;
        var displayReasons = content == null ? null : content.reasons();
        boolean reviewOnly = issue != null || "UNKNOWN".equals(j.getActualDirection());
        if (reviewOnly) {
            displayReasons = JudgementFeedbackRules.articleReview(j.getNews(),j.getReasonText(),j.getChoice());
            if (notice == null) notice = JudgementTargetResolver.resolve(j.getNews()).unavailableReason();
            if (notice == null) notice = "이 기록에는 비교할 가격이 저장되지 않아 적중 여부를 계산하지 않았어요. 기사 내용과 내 근거를 먼저 복습해보세요.";
        }
        if (displayReasons != null && !displayReasons.isEmpty() && displayReasons.getFirst().detail().matches("(?s).*기사 핵심.*(방향도 같았어요|묻혔을 수 있어요|함께 확인해보세요)\\.")) {
            String text = j.getNews().getTitle() + " " + JudgementFeedbackRules.first(new com.finsight.news.collect.ArticleContentExtractor().clean(j.getNews().getTitle(),j.getNews().getRawContent()));
            var enriched = new java.util.ArrayList<>(displayReasons);
            var first = displayReasons.getFirst();
            enriched.set(0,new com.finsight.external.AiFeedbackResponse.Reason(first.title(),first.detail()+" "+JudgementFeedbackRules.priceChannel(text,JudgementFeedbackRules.signal(text,null,null))));
            displayReasons = java.util.List.copyOf(enriched); // Read-only display of old rule copy; stored feedback is untouched.
        }
        return new JudgementHistoryResponse(j.getId(), j.getNews().getId(), j.getNews().getTitle(),
                j.getChoice(), j.getReasonText(), j.getCreatedAt(), j.getActualDirection(), j.getActualChangePercent(),
                reviewOnly ? displayReasons.getFirst().detail() : j.getFeedbackText(), j.getFeedbackGeneratedAt(), j.getComparisonDate(), j.getJudgementClose(), j.getComparisonClose(),
                reviewOnly ? null : j.getFeedbackHeadline(), reviewOnly || content == null ? null : content.verdict(), displayReasons, reviewOnly ? "기사 속 사실과 예상 방향을 구분해 근거를 남겨보세요." : content == null ? null : content.nextTip(),
                symbol, symbolName(symbol),
                j.getActualDirection()==null?expectedResultDate(j.getCreatedAt()):null,
                j.getActualDirection()==null?expectedResultAt(j.getCreatedAt()):null,
                content == null ? null : content.indexName(), content == null ? null : content.indexChangePercent(),
                content == null ? null : content.conceptTerm(), content == null ? null : content.conceptDefinition(),
                notice != null ? notice : "UNKNOWN".equals(j.getActualDirection()) ? "판단 대상이 정해지지 않은 기사예요" : null, issue);
    }

    private String symbolName(String symbol) {
        if ("KOSPI".equals(symbol)) return "코스피";
        if ("KOSDAQ".equals(symbol)) return "코스닥";
        return symbol==null?null:com.finsight.stock.StockCatalog.name(symbol);
    }

    public JudgementHistoryResponse getDetail(User user, Long id) {
        return history(judgementRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "판단 기록을 찾을 수 없어요")));
    }

    public List<JudgementHistoryResponse> getHistory(User user) {
        return judgementRepository.findAllByUserOrderByCreatedAtDesc(user).stream().map(this::history).toList();
    }

    public List<JudgementHistoryResponse> getHistory(User user, RecordDateRange range) {
        return judgementRepository.findInRange(user, range.from(), range.to()).stream().map(this::history).toList();
    }

    public String previousRecord(User user, RecordDateRange range) {
        var date = judgementRepository.previousRecord(user, range.from());
        return date == null ? null : RecordDateRange.date(date);
    }

    public Page<JudgementHistoryResponse> getHistoryPage(User user, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        return judgementRepository.findAllByUserOrderByCreatedAtDesc(user,
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::history);
    }
}
