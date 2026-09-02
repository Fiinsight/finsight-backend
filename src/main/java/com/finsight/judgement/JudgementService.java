package com.finsight.judgement;

import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.util.List;
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

    public JudgementFeedbackResponse recordJudgement(JudgementRequest request) {
        News news = newsRepository.findById(request.newsId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "존재하지 않는 뉴스 id 입니다: " + request.newsId()));

        Judgement judgement = new Judgement(news, request.choice(), request.reason());
        judgementRepository.save(judgement);

        return new JudgementFeedbackResponse(request.newsId(), request.choice(), ACK_FEEDBACK_TEXT);
    }

    public List<JudgementHistoryResponse> getHistory() {
        return judgementRepository.findAllByOrderByCreatedAtDesc().stream()
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
                        j.getFeedbackGeneratedAt()
                ))
                .toList();
    }
}
