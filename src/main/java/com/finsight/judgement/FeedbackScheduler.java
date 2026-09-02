package com.finsight.judgement;

import com.finsight.external.AiFeedbackRequest;
import com.finsight.external.AiServiceClient;
import com.finsight.external.kis.KisStockQuote;
import com.finsight.external.kis.KisStockQuoteClient;
import com.finsight.news.News;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Once a day, after the Korean market closes, looks back at judgements made
 * more than a day ago that don't have feedback yet, figures out what
 * actually happened to the related symbol, and stores an explanatory
 * feedback message on the judgement.
 */
@Component
public class FeedbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedbackScheduler.class);

    private static final double NEUTRAL_BAND_PERCENT = 0.5;

    private final JudgementRepository judgementRepository;
    private final KisStockQuoteClient kisStockQuoteClient;
    private final AiServiceClient aiServiceClient;

    public FeedbackScheduler(JudgementRepository judgementRepository,
                              KisStockQuoteClient kisStockQuoteClient,
                              AiServiceClient aiServiceClient) {
        this.judgementRepository = judgementRepository;
        this.kisStockQuoteClient = kisStockQuoteClient;
        this.aiServiceClient = aiServiceClient;
    }

    // Weekdays, shortly after the KRX market close (~15:30 KST).
    @Scheduled(cron = "0 40 15 * * MON-FRI")
    public void generatePendingFeedback() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        List<Judgement> pending = judgementRepository.findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(cutoff);
        if (pending.isEmpty()) {
            log.info("Feedback scheduler: no pending judgements to process");
            return;
        }

        int succeeded = 0;
        for (Judgement judgement : pending) {
            try {
                processOne(judgement);
                succeeded++;
            } catch (Exception e) {
                log.warn("Failed to generate feedback for judgement {}: {}", judgement.getId(), e.getMessage());
            }
        }
        log.info("Feedback scheduler: processed {}/{} pending judgement(s)", succeeded, pending.size());
    }

    private void processOne(Judgement judgement) {
        News news = judgement.getNews();
        String symbol = news == null ? null : news.getRelatedSymbol();

        String actualDirection;
        Double actualChangePercent;
        if (StringUtils.hasText(symbol)) {
            KisStockQuote quote = kisStockQuoteClient.getStockQuote(symbol);
            actualChangePercent = quote.changePercent();
            actualDirection = classifyDirection(quote.changePercent());
        } else {
            actualDirection = "UNKNOWN";
            actualChangePercent = null;
        }

        String feedbackText = generateFeedbackText(judgement, actualDirection, actualChangePercent);

        judgement.setActualDirection(actualDirection);
        judgement.setActualChangePercent(actualChangePercent);
        judgement.setFeedbackText(feedbackText);
        judgement.setFeedbackGeneratedAt(Instant.now());
        judgementRepository.save(judgement);
    }

    private String classifyDirection(double changePercent) {
        if (changePercent > NEUTRAL_BAND_PERCENT) {
            return "UP";
        }
        if (changePercent < -NEUTRAL_BAND_PERCENT) {
            return "DOWN";
        }
        return "NEUTRAL";
    }

    private String generateFeedbackText(Judgement judgement, String actualDirection, Double actualChangePercent) {
        News news = judgement.getNews();
        if (news == null) {
            // finsight-ai's FeedbackRequest requires a news_id, so without a
            // linked news row there's nothing meaningful to send it.
            return templatedFeedback(judgement, actualDirection, actualChangePercent);
        }

        AiFeedbackRequest request = new AiFeedbackRequest(
                news.getId(),
                judgement.getChoice().name(),
                judgement.getReasonText(),
                actualDirection
        );

        Optional<com.finsight.external.AiFeedbackResponse> aiResponse = aiServiceClient.generateFeedback(request);
        if (aiResponse.isPresent() && StringUtils.hasText(aiResponse.get().feedbackText())) {
            return aiResponse.get().feedbackText();
        }

        return templatedFeedback(judgement, actualDirection, actualChangePercent);
    }

    private String templatedFeedback(Judgement judgement, String actualDirection, Double actualChangePercent) {
        String predicted = judgement.getChoice().name();
        boolean matched = predicted.equals(actualDirection);
        String changeText = actualChangePercent == null
                ? "변동률 정보를 확인하지 못했습니다"
                : String.format("%.2f%%", actualChangePercent);

        if (matched) {
            return String.format(
                    "예측하신 방향(%s)이 실제 결과(%s, %s)와 일치했습니다. 판단 근거를 다시 살펴보며 같은 논리를 다음 판단에도 적용해보세요.",
                    predicted, actualDirection, changeText);
        }
        return String.format(
                "예측하신 방향(%s)과 실제 결과(%s, %s)가 달랐습니다. 어떤 요인을 놓쳤는지 뉴스 내용을 다시 확인해보세요.",
                predicted, actualDirection, changeText);
    }
}
