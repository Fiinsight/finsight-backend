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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Value("${finsight.feedback.on-startup:true}")
    private boolean feedbackOnStartup = true;

    private java.time.Clock clock = java.time.Clock.system(ZoneId.of("Asia/Seoul"));

    public FeedbackScheduler(JudgementRepository judgementRepository,
                              com.finsight.external.kis.KisDailyCandleClient dailyCandles,
                              AiServiceClient aiServiceClient) {
        this.judgementRepository = judgementRepository;
        this.dailyCandles = dailyCandles;
        this.aiServiceClient = aiServiceClient;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!feedbackOnStartup) return;
        java.util.concurrent.CompletableFuture.runAsync(this::generatePendingFeedback)
                .exceptionally(error -> { log.warn("Feedback startup catch-up failed", error); return null; });
    }

    // Weekdays, shortly after the KRX market close (~15:30 KST).
    @Scheduled(cron = "0 40 15-23 * * MON-FRI", zone = "Asia/Seoul")
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

        var feedback = generateFeedback(judgement, actualDirection, actualChangePercent);
        String feedbackText = feedback.feedbackText();
        var content = feedback.verdict() == null ? null : new Judgement.FeedbackContent(feedback.verdict(), feedback.reasons(), feedback.nextTip());
        // Atomic conditional update: concurrent scheduler/manual runs or a revised judgement cannot overwrite an outcome.
        int updated = judgementRepository.finalizeOutcome(judgement.getId(), judgement.getCreatedAt(), actualDirection,
            actualChangePercent, feedbackText, clock.instant(), judgement.getComparisonDate(), judgement.getJudgementClose(), judgement.getComparisonClose(), feedback.headline(), Judgement.feedbackJson(content));
        if (updated != 1) return false;

        judgement.setActualDirection(actualDirection);
        judgement.setActualChangePercent(actualChangePercent);
        judgement.setFeedbackText(feedbackText);
        judgement.setStructuredFeedback(feedback.headline(), content);
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

    private com.finsight.external.AiFeedbackResponse generateFeedback(Judgement judgement, String actualDirection, Double actualChangePercent) {
        News news = judgement.getNews();
        if (news == null || "UNKNOWN".equals(actualDirection)) {
            // finsight-ai's FeedbackRequest requires a news_id, so without a
            // linked news row there's nothing meaningful to send it.
            return ruleFeedback(judgement, actualDirection, actualChangePercent);
        }

        AiFeedbackRequest request = new AiFeedbackRequest(
                news.getId(),
                judgement.getChoice().name(),
                judgement.getReasonText(),
                actualDirection,
                news.getTitle(),
                feedbackSummary(news),
                news.getRelatedSymbol(),
                "KOSPI".equals(news.getRelatedSymbol()) ? "코스피" : "KOSDAQ".equals(news.getRelatedSymbol()) ? "코스닥" : com.finsight.stock.StockCatalog.name(news.getRelatedSymbol()),
                actualChangePercent, judgement.getJudgementClose(), judgement.getComparisonClose(), judgement.getComparisonDate(),
                judgement.getUser() == null || judgement.getUser().getLearningLevel() == null ? "beginner" : judgement.getUser().getLearningLevel(),
                cachedIndexName(news), cachedIndexChange(news, judgement.getComparisonDate()),
                news.getSentimentHint()==null?null:news.getSentimentHint().name(), news.getSentimentConfidence()
        );

        Optional<com.finsight.external.AiFeedbackResponse> aiResponse = aiServiceClient.generateFeedback(request);
        var fallback = ruleFeedback(judgement, actualDirection, actualChangePercent);
        if (aiResponse.isPresent() && StringUtils.hasText(aiResponse.get().summary())) {
            var response = aiResponse.get();
            if (java.util.Objects.equals(fallback.verdict(), response.verdict()) && response.reasons() != null
                    && response.reasons().size() >= 2 && response.reasons().size() <= 3
                    && response.reasons().stream().allMatch(item -> item != null && StringUtils.hasText(item.title()) && item.title().length() <= 16 && StringUtils.hasText(item.detail()))
                    && StringUtils.hasText(response.headline()) && response.headline().length() <= 255 && StringUtils.hasText(response.nextTip())
                    && (StringUtils.hasText(judgement.getReasonText()) || !String.join(" ", response.headline(), response.summary(), response.reasons().toString(), response.nextTip()).matches("(?s).*(정확한 판단|잘했|훌륭|잘 연결).*"))) {
                return response;
            }
            // Rolling deployment compatibility: old AI summary with locally grounded structured fields.
            if (response.verdict() == null && (StringUtils.hasText(judgement.getReasonText()) || !response.summary().matches("(?s).*(정확한 판단|잘했|훌륭|잘 연결).*"))) return new com.finsight.external.AiFeedbackResponse(fallback.verdict(), fallback.headline(),
                    response.summary().replaceAll("\\bUP\\b", "상승").replaceAll("\\bDOWN\\b", "하락").replaceAll("\\bNEUTRAL\\b", "중립").replaceAll("\\bUNKNOWN\\b", "확인 불가"), fallback.reasons(), fallback.nextTip());
        }
        return fallback;
    }

    private String cachedIndexName(News news) {
        return "KOSDAQ".equals(com.finsight.stock.StockCatalog.market(news.getRelatedSymbol())) ? "코스닥" : "코스피";
    }
    private Double cachedIndexChange(News news, LocalDate day) {
        if (cacheManager == null || day == null || "KOSPI".equals(news.getRelatedSymbol()) || "KOSDAQ".equals(news.getRelatedSymbol())) return null;
        return com.finsight.chart.ChartService.cachedIndexChange(cacheManager.getCache("kisDailyCandles"), "코스닥".equals(cachedIndexName(news)) ? "KOSDAQ" : "KOSPI", day);
    }

    private String feedbackSummary(News news) {
        String cleaned = new com.finsight.news.collect.ArticleContentExtractor().clean(news.getTitle(),
                StringUtils.hasText(news.getRewrittenNormal()) ? news.getRewrittenNormal() : news.getRawContent());
        String excerpt = com.finsight.news.NewsDetailResponse.excerpt(cleaned);
        return excerpt.substring(0, Math.min(600, excerpt.length()));
    }

    private String templatedFeedback(Judgement judgement, String actualDirection, Double actualChangePercent) {
        if ("UNKNOWN".equals(actualDirection)) return "관련 종목이 없어 결과를 비교하지 않았어요";
        String predicted = judgement.getChoice().name();
        boolean matched = predicted.equals(actualDirection);
        String changeText = actualChangePercent == null
                ? "변동률 정보를 확인하지 못했습니다"
                : String.format(java.util.Locale.ROOT, "%+.2f%%", actualChangePercent);

        String symbol = judgement.getNews() == null ? null : judgement.getNews().getRelatedSymbol();
        String name = "KOSPI".equals(symbol) ? "코스피" : "KOSDAQ".equals(symbol) ? "코스닥" : com.finsight.stock.StockCatalog.name(symbol);
        String result = String.format("%s %s 종가 기준 %s. 예측(%s)은 실제 결과(%s)와 %s 방향이에요.",
                name, judgement.getComparisonDate(), changeText, directionLabel(predicted), directionLabel(actualDirection), matched ? "같은" : "다른");
        result += StringUtils.hasText(judgement.getReasonText()) ? " 남긴 근거 “" + judgement.getReasonText().substring(0, Math.min(200, judgement.getReasonText().length())) + "”와 기사 핵심 “" + feedbackSummary(judgement.getNews()).split("(?<=[.!?])\\s+", 2)[0] + "”를 비교하고 숫자의 기준 시점을 확인해보세요." : " 다음엔 판단 근거를 한 줄 남겨보세요.";
        if (actualChangePercent != null && Math.abs(actualChangePercent) <= NEUTRAL_BAND_PERCENT + 0.5 + 1e-9)
            result += matched ? " 방향은 맞았지만 변동이 작아 우연일 수 있어요." : " 변동이 작아 방향 차이만으로 근거를 평가하기 어려워요.";
        if ("KOSPI".equals(symbol) || "KOSDAQ".equals(symbol)) result += " 개별 종목이 아니라 시장 전체 움직임을 비교한 결과예요.";
        String level = judgement.getUser() == null ? null : judgement.getUser().getLearningLevel();
        if (level == null || "beginner".equals(level)) result += " 종가는 거래가 끝날 때의 가격이고, 변동률은 두 가격이 달라진 비율이에요.";
        if ("analyst".equals(level)) result += " 변동률·판단 기준 종가·비교 종가와 중립 범위 ±0.5%를 함께 확인하세요.";
        return result + " 가격 변화가 이 뉴스 때문에 생겼다고 단정할 수는 없어요.";
    }
    private com.finsight.external.AiFeedbackResponse ruleFeedback(Judgement judgement, String direction, Double percent) {
        String summary = templatedFeedback(judgement, direction, percent);
        if ("UNKNOWN".equals(direction)) return new com.finsight.external.AiFeedbackResponse(summary);
        boolean aligned = judgement.getChoice().name().equals(direction);
        String verdict = aligned && percent != null && Math.abs(percent) <= NEUTRAL_BAND_PERCENT + 0.5 + 1e-9 ? "PARTIAL" : aligned ? "CORRECT" : "WRONG";
        boolean hasReason = StringUtils.hasText(judgement.getReasonText());
        String headline = "PARTIAL".equals(verdict) ? "방향은 맞았지만 변동이 작았어요" : "WRONG".equals(verdict) ? "다르게 움직였어요" : hasReason ? "정확한 판단이에요!" : "예측 방향이 같았어요";
        String core = feedbackSummary(judgement.getNews()).split("(?<=[.!?])\\s+", 2)[0];
        String reason = hasReason ? judgement.getReasonText().split("(?<=[.!?])\\s+|\\n+", 2)[0] : "";
        String market = summary.split("\\. ", 2)[0] + " (판단 기준 종가 " + judgement.getJudgementClose() + " → 결과 종가 " + judgement.getComparisonClose() + ").";
        String indexSymbol = "KOSDAQ".equals(com.finsight.stock.StockCatalog.market(judgement.getNews().getRelatedSymbol())) ? "KOSDAQ" : "KOSPI";
        Double index = cachedIndexChange(judgement.getNews(), judgement.getComparisonDate());
        if (index != null && !java.util.Set.of("KOSPI", "KOSDAQ").contains(judgement.getNews().getRelatedSymbol()))
            market = market.substring(0, market.length() - 1) + String.format(java.util.Locale.ROOT, ", 같은 비교 구간 %s %+.2f%%.", "KOSDAQ".equals(indexSymbol) ? "코스닥" : "코스피", index);
        String note = com.finsight.news.SentimentEvidence.note(judgement.getNews().getSentimentHint(), judgement.getNews().getSentimentConfidence());
        String articleDetail = core.isBlank() ? "저장된 기사 근거를 확인할 수 없어요." : note.isBlank() ? "기사 핵심 “" + core + "”를 실제 가격 변화와 함께 확인해보세요." : "기사 핵심 “" + core + "”를 확인하되, " + note;
        var reasons = List.of(new com.finsight.external.AiFeedbackResponse.Reason("기사 근거", articleDetail),
                new com.finsight.external.AiFeedbackResponse.Reason(hasReason ? "판단 근거" : "근거 남기기", hasReason ? "남긴 근거 “" + reason + "”의 기준 시점이 기사와 같은지 확인해보세요." : "다음엔 판단 근거를 한 줄 남겨보세요."),
                new com.finsight.external.AiFeedbackResponse.Reason("실제 시장", market));
        return new com.finsight.external.AiFeedbackResponse(verdict, headline, summary, reasons, hasReason ? "같은 비교 시점의 기사 근거와 시장 흐름을 함께 확인해보세요." : "다음엔 판단 근거를 한 줄 남겨보세요.");
    }

    private String directionLabel(String direction) {
        return switch (direction) { case "UP" -> "상승"; case "DOWN" -> "하락"; case "NEUTRAL" -> "중립"; default -> "확인 불가"; };
    }

}
