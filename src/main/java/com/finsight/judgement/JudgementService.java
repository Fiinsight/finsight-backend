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

        // Lock the news row until commit; prevents concurrent inserts before the deferred unique constraint.
        newsRepository.lockForJudgement(news.getId());
        var existing = judgementRepository.findFirstByUserAndNewsIdOrderByCreatedAtDescIdDesc(user, news.getId());
        if (existing.isPresent()) {
            var judgement = existing.get();
            if (judgement.getActualDirection() != null || judgement.getFeedbackGeneratedAt() != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 결과가 나온 판단이에요");
            judgement.revise(request.choice(), request.reason());
            judgementRepository.save(judgement);
            return acknowledgment(judgement, "판단을 수정했어요");
        }
        Judgement judgement = new Judgement(user, news, request.choice(), request.reason());
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
        return new JudgementHistoryResponse(j.getId(), j.getNews().getId(), j.getNews().getTitle(),
                j.getChoice(), j.getReasonText(), j.getCreatedAt(), j.getActualDirection(), j.getActualChangePercent(),
                j.getFeedbackText(), j.getFeedbackGeneratedAt(), j.getComparisonDate(), j.getJudgementClose(), j.getComparisonClose(),
                j.getFeedbackHeadline(), content == null ? null : content.verdict(), content == null ? null : content.reasons(), content == null ? null : content.nextTip(),
                j.getNews().getRelatedSymbol(), symbolName(j.getNews().getRelatedSymbol()),
                j.getActualDirection()==null?expectedResultDate(j.getCreatedAt()):null,
                j.getActualDirection()==null?expectedResultAt(j.getCreatedAt()):null);
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
