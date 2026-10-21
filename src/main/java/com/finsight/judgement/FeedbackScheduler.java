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
        if (java.util.Objects.toString(judgement.getNews().getTitle(), "").contains("로컬 QA")) return false;
        LocalDate judgementDay = judgement.getCreatedAt().atZone(now.getZone()).toLocalDate();
        LocalDate comparison = KoreanTradingCalendar.next(judgementDay);
        LocalDate baseline = KoreanTradingCalendar.onOrBefore(judgementDay);
        if (comparison.isAfter(now.toLocalDate()) || (comparison.equals(now.toLocalDate()) && now.toLocalTime().isBefore(java.time.LocalTime.of(15, 40)))) return false;
        String symbol = judgement.getTargetSymbol();
        if (!StringUtils.hasText(symbol)) symbol = JudgementTargetResolver.resolve(judgement.getNews()).symbol();
        if (!JudgementTargetResolver.valid(symbol)) return finishWithoutTarget(judgement, symbol);
        var prices = closingPrices(symbol, baseline, comparison);
        if (prices.isEmpty()) return false;
        boolean indexTarget = symbol.equals("KOSPI") || symbol.equals("KOSDAQ");
        String indexSymbol = "KOSDAQ".equals(com.finsight.stock.StockCatalog.market(symbol)) ? "KOSDAQ" : "KOSPI";
        var indexPrices = indexTarget ? Optional.<ClosingPrices.Pair>empty() : closingPrices(indexSymbol, baseline, comparison);
        if (!indexTarget && indexPrices.isEmpty()) return false;
        double actualChangePercent = prices.get().percent();
        String actualDirection = JudgementFeedbackRules.direction(actualChangePercent);
        String indexName = indexTarget ? null : JudgementTargetResolver.name(indexSymbol);
        Double indexChange = indexPrices.map(ClosingPrices.Pair::percent).orElse(null);
        var feedback = generateFeedback(judgement, symbol, comparison, prices.get(), indexName, indexChange);
        String feedbackText = feedback.feedbackText();
        var content = new Judgement.FeedbackContent(feedback.verdict(), feedback.reasons(), feedback.nextTip(),
                indexName, indexChange, feedback.conceptTerm(), feedback.conceptDefinition());
        // Atomic conditional update: concurrent scheduler/manual runs or a revised judgement cannot overwrite an outcome.
        int updated = judgementRepository.finalizeOutcome(judgement.getId(), judgement.getCreatedAt(), actualDirection,
            actualChangePercent, feedbackText, clock.instant(), comparison, java.math.BigDecimal.valueOf(prices.get().before()), java.math.BigDecimal.valueOf(prices.get().after()), feedback.headline(), Judgement.feedbackJson(content), symbol);
        if (updated != 1) return false;

        judgement.setTargetSymbol(symbol);
        judgement.setClosingComparison(comparison, prices.get().before(), prices.get().after());
        judgement.setActualDirection(actualDirection);
        judgement.setActualChangePercent(actualChangePercent);
        judgement.setFeedbackText(feedbackText);
        judgement.setStructuredFeedback(feedback.headline(), content);
        judgement.setFeedbackGeneratedAt(clock.instant());
        return true;
    }

    public JudgementRegradeResponse regradeUnknown(Long userId, boolean apply) {
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        var rows = new java.util.ArrayList<JudgementRegradeResponse.Row>();
        long ready = 0;
        for (var judgement : judgementRepository.findUnknownByOwner(userId)) {
            var row = new JudgementRegradeResponse.Row(judgement.getId(),"FAILED",null,null,null,null);
            try {
                row = previewUnknown(judgement,userId);
                if ("READY".equals(row.status())) {
                    ready++;
                    if (apply) row = applyRegrade(judgement,userId,row);
                }
            } catch (Exception error) {
                row = row.withStatus("FAILED","종가 조회·피드백 생성·저장 중 실패해 재채점하지 않았어요.");
                log.warn("UNKNOWN regrade {} failed: {}", judgement.getId(), error.getClass().getSimpleName());
            }
            rows.add(row);
        }
        return new JudgementRegradeResponse(!apply,ready,rows.stream().filter(r -> "APPLIED".equals(r.status())).count(),List.copyOf(rows));
    }

    private JudgementRegradeResponse.Row previewUnknown(Judgement judgement, Long userId) {
        if (judgement.getUser() == null || !userId.equals(judgement.getUser().getId())
                || !"UNKNOWN".equals(judgement.getActualDirection()) || judgement.getComparisonDate() != null
                || java.util.Objects.toString(judgement.getNews().getTitle(), "").contains("로컬 QA"))
            return new JudgementRegradeResponse.Row(judgement.getId(),"SKIPPED","재채점 대상 기록이 아니에요.",null,null,null);
        var target = JudgementTargetResolver.resolve(judgement.getNews());
        var now = java.time.ZonedDateTime.now(clock);
        var judged = judgement.getCreatedAt().atZone(now.getZone()).toLocalDate();
        LocalDate baseline = KoreanTradingCalendar.onOrBefore(judged), comparison = KoreanTradingCalendar.next(judged);
        var row = new JudgementRegradeResponse.Row(judgement.getId(),"NO_TARGET",target.unavailableReason(),target.symbol(),baseline,comparison);
        if (!JudgementTargetResolver.valid(target.symbol())) return row;
        if (comparison.isAfter(now.toLocalDate()) || (comparison.equals(now.toLocalDate()) && now.toLocalTime().isBefore(java.time.LocalTime.of(15,40))))
            return row.withStatus("NOT_DUE","다음 거래일 장 마감 종가가 아직 확정되지 않았어요.");
        var prices = closingPrices(target.symbol(),baseline,comparison).orElse(null);
        if (prices == null) return row.withStatus("NO_TARGET_CLOSES","대상의 기준일·비교일 실제 종가가 모두 조회되지 않았어요.");
        boolean indexTarget = List.of("KOSPI","KOSDAQ").contains(target.symbol());
        String index = indexTarget ? null : "KOSDAQ".equals(com.finsight.stock.StockCatalog.market(target.symbol())) ? "KOSDAQ" : "KOSPI";
        var indexPrices = indexTarget ? null : closingPrices(index,baseline,comparison).orElse(null);
        boolean ready = indexTarget || indexPrices != null;
        return new JudgementRegradeResponse.Row(judgement.getId(),ready ? "READY" : "NO_INDEX_CLOSES",
                ready ? null : "시장 지수의 기준일·비교일 실제 종가가 모두 조회되지 않았어요.",target.symbol(),baseline,comparison,
                prices.before(),prices.after(),JudgementFeedbackRules.direction(prices.percent()),prices.percent(),index,
                indexPrices == null ? null : indexPrices.before(),indexPrices == null ? null : indexPrices.after(),indexPrices == null ? null : indexPrices.percent());
    }

    private JudgementRegradeResponse.Row applyRegrade(Judgement judgement, Long userId, JudgementRegradeResponse.Row row) {
        var prices = new ClosingPrices.Pair(row.judgementClose(),row.comparisonClose());
        String indexName = row.indexSymbol() == null ? null : JudgementTargetResolver.name(row.indexSymbol());
        var feedback = generateFeedback(judgement,row.targetSymbol(),row.comparisonDate(),prices,indexName,row.indexChangePercent());
        var content = new Judgement.FeedbackContent(feedback.verdict(),feedback.reasons(),feedback.nextTip(),indexName,
                row.indexChangePercent(),feedback.conceptTerm(),feedback.conceptDefinition());
        int updated = judgementRepository.regradeUnknownOutcome(judgement.getId(),userId,judgement.getCreatedAt(),row.targetSymbol(),
                row.actualDirection(),row.actualChangePercent(),row.comparisonDate(),java.math.BigDecimal.valueOf(row.judgementClose()),
                java.math.BigDecimal.valueOf(row.comparisonClose()),feedback.feedbackText(),feedback.headline(),Judgement.feedbackJson(content),clock.instant());
        return row.withStatus(updated == 1 ? "APPLIED" : "CONFLICT",updated == 1 ? null : "이미 재채점됐거나 판단이 변경돼 저장하지 않았어요.");
    }

    private boolean finishWithoutTarget(Judgement judgement, String symbol) {
        String reason = "판단 대상이 정해지지 않은 기사예요";
        var content = new Judgement.FeedbackContent(null,
                JudgementFeedbackRules.articleReview(judgement.getNews(), judgement.getReasonText(), judgement.getChoice()),
                "국내 종목이나 시장에 연결되는 다른 기사에서 판단을 남겨보세요.");
        if (judgementRepository.finalizeOutcome(judgement.getId(), judgement.getCreatedAt(), "UNKNOWN", null, reason,
                clock.instant(), null, null, null, "판단 대상이 없어요", Judgement.feedbackJson(content), symbol) != 1) return false;
        judgement.setTargetSymbol(symbol);
        judgement.setActualDirection("UNKNOWN");
        judgement.setFeedbackText(reason);
        judgement.setStructuredFeedback("판단 대상이 없어요", content);
        judgement.setFeedbackGeneratedAt(clock.instant());
        return true;
    }

    private Optional<ClosingPrices.Pair> closingPrices(String symbol, LocalDate before, LocalDate after) {
        var cached = ClosingPrices.cached(cacheManager == null ? null : cacheManager.getCache("kisDailyCandles"), symbol, before, after);
        return cached.isPresent() ? cached : ClosingPrices.from(dailyCandles.getClosingCandles(symbol, before, after), before, after);
    }

    private com.finsight.external.AiFeedbackResponse generateFeedback(Judgement judgement, String symbol, LocalDate comparison,
                                                                       ClosingPrices.Pair prices, String indexName, Double indexChange) {
        double percent = prices.percent();
        String direction = JudgementFeedbackRules.direction(percent);
        News news = judgement.getNews();
        String level = judgement.getUser() == null || judgement.getUser().getLearningLevel() == null ? "beginner" : judgement.getUser().getLearningLevel();
        var concept = learning == null || judgement.getUser() == null ? null : learning.feedbackConcept(judgement.getUser().getId(), news, level);
        var request = new AiFeedbackRequest(news.getId(), judgement.getChoice().name(), judgement.getReasonText(), direction,
                news.getTitle(), feedbackSummary(news), symbol, JudgementTargetResolver.name(symbol),
                percent, java.math.BigDecimal.valueOf(prices.before()), java.math.BigDecimal.valueOf(prices.after()), comparison, level,
                indexName, indexChange, news.getSentimentHint() == null ? null : news.getSentimentHint().name(), news.getSentimentConfidence(),
                concept == null ? null : concept.term(), concept == null ? null : concept.definition(), judgement.getId());
        var fallback = JudgementFeedbackRules.generate(request);
        Optional<com.finsight.external.AiFeedbackResponse> answer;
        try { answer = aiServiceClient.generateFeedback(request); }
        catch (Exception unavailable) { return fallback; }
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
