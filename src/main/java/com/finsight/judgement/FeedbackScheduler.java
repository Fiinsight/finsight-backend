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

    private final JudgementRepository judgementRepository;
    private final com.finsight.external.kis.KisDailyCandleClient dailyCandles;
    private final AiServiceClient aiServiceClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.finsight.learning.LearningService learning;

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
        // Old rows are changed only by the separately approved regrade, never startup catch-up.
        String symbol = judgement.getTargetSymbol();
        if (!JudgementTargetResolver.valid(symbol)) return false;
        var prices = closingPrices(symbol, baseline, comparison);
        if (prices.isEmpty()) return false;
        boolean indexTarget = symbol.equals("KOSPI") || symbol.equals("KOSDAQ");
        String indexSymbol = "KOSDAQ".equals(com.finsight.stock.StockCatalog.market(symbol)) ? "KOSDAQ" : "KOSPI";
        var indexPrices = indexTarget ? Optional.<ClosingPrices.Pair>empty() : closingPrices(indexSymbol, baseline, comparison);
        if (!indexTarget && indexPrices.isEmpty()) return false;
        double actualChangePercent = prices.get().percent();
        String actualDirection = JudgementFeedbackRules.direction(actualChangePercent);
        judgement.setClosingComparison(comparison, prices.get().before(), prices.get().after());
        String indexName = indexTarget ? null : JudgementTargetResolver.name(indexSymbol);
        Double indexChange = indexPrices.map(ClosingPrices.Pair::percent).orElse(null);
        var feedback = generateFeedback(judgement, actualDirection, actualChangePercent, indexName, indexChange);
        String feedbackText = feedback.feedbackText();
        var content = new Judgement.FeedbackContent(feedback.verdict(), feedback.reasons(), feedback.nextTip(),
                indexName, indexChange, feedback.conceptTerm(), feedback.conceptDefinition());
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

    private Optional<ClosingPrices.Pair> closingPrices(String symbol, LocalDate before, LocalDate after) {
        var cached = ClosingPrices.cached(cacheManager == null ? null : cacheManager.getCache("kisDailyCandles"), symbol, before, after);
        return cached.isPresent() ? cached : ClosingPrices.from(dailyCandles.getClosingCandles(symbol, before, after), before, after);
    }

    private com.finsight.external.AiFeedbackResponse generateFeedback(Judgement judgement, String direction, double percent,
                                                                       String indexName, Double indexChange) {
        News news = judgement.getNews();
        String level = judgement.getUser() == null || judgement.getUser().getLearningLevel() == null ? "beginner" : judgement.getUser().getLearningLevel();
        var concept = learning == null || judgement.getUser() == null ? null : learning.feedbackConcept(judgement.getUser().getId(), news, level);
        var request = new AiFeedbackRequest(news.getId(), judgement.getChoice().name(), judgement.getReasonText(), direction,
                news.getTitle(), feedbackSummary(news), judgement.getTargetSymbol(), JudgementTargetResolver.name(judgement.getTargetSymbol()),
                percent, judgement.getJudgementClose(), judgement.getComparisonClose(), judgement.getComparisonDate(), level,
                indexName, indexChange, news.getSentimentHint() == null ? null : news.getSentimentHint().name(), news.getSentimentConfidence(),
                concept == null ? null : concept.term(), concept == null ? null : concept.definition(), judgement.getId());
        var fallback = JudgementFeedbackRules.generate(request);
        Optional<com.finsight.external.AiFeedbackResponse> answer = aiServiceClient.generateFeedback(request);
        if (answer.isEmpty()) return fallback;
        var response = answer.get();
        if (!java.util.Objects.equals(fallback.verdict(),response.verdict()) || response.reasons() == null
                || response.reasons().size() != 3 || !StringUtils.hasText(response.summary()) || !StringUtils.hasText(response.headline())
                || response.headline().length() > 255 || !StringUtils.hasText(response.nextTip())
                || !java.util.Objects.equals(request.conceptTerm(), response.conceptTerm())
                || response.reasons().stream().anyMatch(r -> r == null || !StringUtils.hasText(r.title()) || r.title().length() > 16 || !StringUtils.hasText(r.detail()))) return fallback;
        String text = response.headline() + " " + response.summary() + " " + response.reasons() + " " + response.nextTip();
        if (!text.contains(JudgementFeedbackRules.signed(percent)) || (indexChange != null && !text.contains(JudgementFeedbackRules.signed(indexChange)))
                || (!StringUtils.hasText(judgement.getReasonText()) && text.matches("(?s).*(정확한 판단|잘했|훌륭|잘 연결).*"))) return fallback;
        return new com.finsight.external.AiFeedbackResponse(response.verdict(),response.headline(),response.summary(),response.reasons(),response.nextTip(),
                request.conceptTerm(),request.conceptDefinition(),response.mode());
    }
    private String feedbackSummary(News news) {
        String cleaned = new com.finsight.news.collect.ArticleContentExtractor().clean(news.getTitle(), news.getRawContent());
        return cleaned == null ? "" : cleaned.substring(0, Math.min(600, cleaned.length()));
    }
}
