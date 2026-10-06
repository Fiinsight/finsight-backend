package com.finsight.judgement;

import com.finsight.external.AiFeedbackRequest;
import com.finsight.external.AiServiceClient;
import com.finsight.news.News;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Once a day, after the Korean market closes, looks back at judgements that
 * don't have feedback yet, figures out what
 * actually happened to the related symbol, and stores an explanatory
 * feedback message on the judgement.
 */
@Component
public class FeedbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedbackScheduler.class);

    private static final double NEUTRAL_BAND_PERCENT = 0.5;

    private final JudgementRepository judgementRepository;
    private final com.finsight.external.kis.KisDailyCandleClient dailyCandles;
    private final AiServiceClient aiServiceClient;

    private java.time.Clock clock = java.time.Clock.system(ZoneId.of("Asia/Seoul"));

    public FeedbackScheduler(JudgementRepository judgementRepository,
                              com.finsight.external.kis.KisDailyCandleClient dailyCandles,
                              AiServiceClient aiServiceClient) {
        this.judgementRepository = judgementRepository;
        this.dailyCandles = dailyCandles;
        this.aiServiceClient = aiServiceClient;
    }

    // Weekdays, shortly after the KRX market close (~15:30 KST).
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void generatePendingFeedback() {
        Instant today = LocalDate.now(clock).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        process(judgementRepository.findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(today));
    }

    /**
     * Same processing as the scheduled run, but without the KST calendar-day gate — lets {@code POST /api/judgements/generate-feedback-now}
     * exercise the real flow on demand instead of waiting for tomorrow's
     * 15:40 cron to prove it actually works.
     */
    public int runNow(Long userId) {
        Instant cutoff = LocalDate.now(clock).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        return process(judgementRepository.findPendingByOwner(userId, cutoff));
    }

    private int process(List<Judgement> pending) {
        if (pending.isEmpty()) {
            log.info("Feedback scheduler: no pending judgements to process");
            return 0;
        }

        int succeeded = 0;
        for (Judgement judgement : pending) {
            try {
                if (processOne(judgement)) succeeded++;
            } catch (Exception e) {
                log.warn("Failed to generate feedback for judgement {}: {}", judgement.getId(), e.getMessage());
            }
        }
        log.info("Feedback scheduler: processed {}/{} pending judgement(s)", succeeded, pending.size());
        return succeeded;
    }

    private boolean processOne(Judgement judgement) {
        var now = java.time.ZonedDateTime.now(clock);
        if (judgement.getFeedbackGeneratedAt() != null || judgement.getActualDirection() != null) return false;
        LocalDate judgementDay = judgement.getCreatedAt().atZone(now.getZone()).toLocalDate();
        LocalDate comparison = KoreanTradingCalendar.next(judgementDay);
        LocalDate baseline = KoreanTradingCalendar.onOrBefore(judgementDay);
        if (comparison.isAfter(now.toLocalDate()) || (comparison.equals(now.toLocalDate()) && now.toLocalTime().isBefore(java.time.LocalTime.of(15, 40)))) return false;
        News news = judgement.getNews();
        String symbol = news == null ? null : news.getRelatedSymbol();

        String actualDirection;
        Double actualChangePercent;
        if (StringUtils.hasText(symbol)) {
            var result = dailyCandles.getClosingCandles(symbol, baseline, comparison);
            if (result == null || result.fallback()) return false;
            var before = result.candles().stream().filter(c -> baseline.equals(c.date())).findFirst();
            var after = result.candles().stream().filter(c -> comparison.equals(c.date())).findFirst();
            if (before.isEmpty() || after.isEmpty() || !Double.isFinite(before.get().close()) || before.get().close() <= 0
                    || !Double.isFinite(after.get().close()) || after.get().close() <= 0) return false;
            actualChangePercent = (after.get().close() - before.get().close()) / before.get().close() * 100;
            actualDirection = classifyDirection(actualChangePercent);
            judgement.setClosingComparison(comparison, before.get().close(), after.get().close());
        } else {
            actualDirection = "UNKNOWN";
            actualChangePercent = null;
        }

        String feedbackText = generateFeedbackText(judgement, actualDirection, actualChangePercent);
        // Atomic conditional update: concurrent scheduler/manual runs or a revised judgement cannot overwrite an outcome.
        int updated = judgementRepository.finalizeOutcome(judgement.getId(), judgement.getCreatedAt(), actualDirection,
            actualChangePercent, feedbackText, clock.instant(), judgement.getComparisonDate(), judgement.getJudgementClose(), judgement.getComparisonClose());
        if (updated != 1) return false;

        judgement.setActualDirection(actualDirection);
        judgement.setActualChangePercent(actualChangePercent);
        judgement.setFeedbackText(feedbackText);
        judgement.setFeedbackGeneratedAt(clock.instant());
        return true;
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
        if (news == null || "UNKNOWN".equals(actualDirection)) {
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
            return aiResponse.get().feedbackText().replaceAll("\\bUP\\b", "상승").replaceAll("\\bDOWN\\b", "하락")
                    .replaceAll("\\bNEUTRAL\\b", "중립").replaceAll("\\bUNKNOWN\\b", "확인 불가");
        }

        return templatedFeedback(judgement, actualDirection, actualChangePercent);
    }

    private String templatedFeedback(Judgement judgement, String actualDirection, Double actualChangePercent) {
        if ("UNKNOWN".equals(actualDirection)) return "관련 종목이 없어 결과를 비교하지 않았어요";
        String predicted = judgement.getChoice().name();
        boolean matched = predicted.equals(actualDirection);
        String changeText = actualChangePercent == null
                ? "변동률 정보를 확인하지 못했습니다"
                : String.format("%.2f%%", actualChangePercent);

        if (matched) {
            return String.format(
                    "예측하신 방향(%s)이 실제 결과(%s, %s)와 일치했습니다. 판단 근거를 다시 살펴보며 같은 논리를 다음 판단에도 적용해보세요.",
                    directionLabel(predicted), directionLabel(actualDirection), changeText);
        }
        return String.format(
                "예측하신 방향(%s)과 실제 결과(%s, %s)가 달랐습니다. 어떤 요인을 놓쳤는지 뉴스 내용을 다시 확인해보세요.",
                directionLabel(predicted), directionLabel(actualDirection), changeText);
    }
    private String directionLabel(String direction) {
        return switch (direction) { case "UP" -> "상승"; case "DOWN" -> "하락"; case "NEUTRAL" -> "중립"; default -> "확인 불가"; };
    }

}
