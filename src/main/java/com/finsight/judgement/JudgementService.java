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
            "판단이 기록되었습니다. 다음날 결과를 확인해보세요.";

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
            return new JudgementFeedbackResponse(request.newsId(), request.choice(), "판단을 수정했어요");
        }
        Judgement judgement = new Judgement(user, news, request.choice(), request.reason());
        judgementRepository.save(judgement);

        return new JudgementFeedbackResponse(request.newsId(), request.choice(), ACK_FEEDBACK_TEXT);
    }

    public List<JudgementHistoryResponse> getHistory(User user) {
        return judgementRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
                .map(j -> new JudgementHistoryResponse(
                        j.getId(),
                        j.getNews().getId(),
                        j.getNews().getTitle(),
                        j.getChoice(),
                        j.getReasonText(),
                        j.getCreatedAt(),
                        j.getActualDirection(),
                        j.getActualChangePercent(),
                        j.getFeedbackText(),
                        j.getFeedbackGeneratedAt(), j.getComparisonDate(), j.getJudgementClose(), j.getComparisonClose()
                ))
                .toList();
    }

    public List<JudgementHistoryResponse> getHistory(User user, RecordDateRange range) {
        return judgementRepository.findInRange(user, range.from(), range.to()).stream()
                .map(j -> new JudgementHistoryResponse(j.getId(), j.getNews().getId(), j.getNews().getTitle(),
                        j.getChoice(), j.getReasonText(), j.getCreatedAt(), j.getActualDirection(),
                        j.getActualChangePercent(), j.getFeedbackText(), j.getFeedbackGeneratedAt(), j.getComparisonDate(), j.getJudgementClose(), j.getComparisonClose())).toList();
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
                .map(j -> new JudgementHistoryResponse(
                        j.getId(),
                        j.getNews().getId(),
                        j.getNews().getTitle(),
                        j.getChoice(),
                        j.getReasonText(),
                        j.getCreatedAt(),
                        j.getActualDirection(),
                        j.getActualChangePercent(),
                        j.getFeedbackText(),
                        j.getFeedbackGeneratedAt(), j.getComparisonDate(), j.getJudgementClose(), j.getComparisonClose()
                ));
    }
}
