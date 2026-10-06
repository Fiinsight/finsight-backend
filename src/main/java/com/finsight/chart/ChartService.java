package com.finsight.chart;

import com.finsight.chart.ChartResponse.CandleView;
import com.finsight.chart.ChartResponse.NewsMarkerView;
import com.finsight.external.kis.KisDailyCandle;
import com.finsight.external.kis.KisDailyCandleClient;
import com.finsight.external.kis.KisDailyCandleResult;
import com.finsight.external.kis.KisMinuteCandleClient;
import com.finsight.external.kis.KisMinuteCandleResult;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import com.finsight.briefing.SentimentHint;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.collect.NewsSymbolMatcher;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ChartService {

    private static final Logger log = LoggerFactory.getLogger(ChartService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DAILY_CANDLE_COUNT = 30;
    private static final int WEEKLY_CANDLE_COUNT = 7; // enough bars for a five-return baseline
    private static final int VOLATILITY_WINDOW = 20;
    private static final int MIN_VOLATILITY_OBSERVATIONS = 5;
    private static final double VOLATILITY_MULTIPLIER = 2.0;

    private final KisDailyCandleClient kisDailyCandleClient;
    private final KisMinuteCandleClient kisMinuteCandleClient;
    private final NewsRepository newsRepository;
    private final ArticleContentExtractor articleContentExtractor;
    private final NewsSymbolMatcher newsSymbolMatcher;

    public ChartService(KisDailyCandleClient kisDailyCandleClient,
                        KisMinuteCandleClient kisMinuteCandleClient,
                        NewsRepository newsRepository,
                        ArticleContentExtractor articleContentExtractor,
                        NewsSymbolMatcher newsSymbolMatcher) {
        this.kisDailyCandleClient = kisDailyCandleClient;
        this.kisMinuteCandleClient = kisMinuteCandleClient;
        this.newsRepository = newsRepository;
        this.articleContentExtractor = articleContentExtractor;
        this.newsSymbolMatcher = newsSymbolMatcher;
    }

    public ChartResponse getChart(String symbol) {
        return getChart(symbol, "D", 5);
    }

    /**
     * @param period "D"(일봉) or "W"(주봉)
     */
    public ChartResponse getChart(String symbol, String period) {
        return getChart(symbol, period, 5);
    }

    @Cacheable(cacheNames = "chartResponses", key = "#symbol + ' : ' + #period + ' : ' + #intervalMinutes", sync = true)
    public ChartResponse getChart(String symbol, String period, int intervalMinutes) {
        if ("MINUTE".equalsIgnoreCase(period) || "M".equalsIgnoreCase(period)) {
            int interval = intervalMinutes == 1 || intervalMinutes == 5 || intervalMinutes == 15 ? intervalMinutes : 5;
            KisMinuteCandleResult result = kisMinuteCandleClient.getCandlesWithStatus(symbol, interval, 120);
            List<ChartResponse.MinuteCandleView> minuteCandles = result.candles().stream()
                    .map(c -> new ChartResponse.MinuteCandleView(c.timestamp(), c.open(), c.high(), c.low(), c.close()))
                    .toList();
            double price = minuteCandles.isEmpty() ? 0.0 : minuteCandles.get(minuteCandles.size() - 1).close();
            double changePercent = computeMinuteChangePercent(minuteCandles);
            return new ChartResponse(symbol, price, changePercent, List.of(), List.of(), List.of(), null,
                    "MINUTE", interval, minuteCandles, result.fallback(),
                    result.fallback() ? List.of() : findMoveInsights(symbol, result.candles()));
        }

        String periodDivCode = "W".equalsIgnoreCase(period) ? "W" : "D";
        int count = "W".equals(periodDivCode) ? WEEKLY_CANDLE_COUNT : DAILY_CANDLE_COUNT;
        KisDailyCandleResult candleResult = kisDailyCandleClient.getCandlesWithStatus(symbol, count, periodDivCode);
        List<KisDailyCandle> candles = candleResult.candles();

        List<CandleView> candleViews = candles.stream()
                .sorted(Comparator.comparing(KisDailyCandle::date))
                .map(c -> new CandleView(c.date(), c.open(), c.high(), c.low(), c.close()))
                .toList();

        double price = candleViews.isEmpty() ? 0.0 : candleViews.get(candleViews.size() - 1).close();
        double changePercent = computeChangePercent(candleViews);

        List<News> matchedNews;
        if (candleViews.isEmpty()) {
            matchedNews = List.of();
        } else {
            LocalDate startDate = candleViews.get(0).date();
            LocalDate endDate = candleViews.get(candleViews.size() - 1).date();
            Instant start = com.finsight.judgement.KoreanTradingCalendar.previous(startDate).atTime(15, 30).atZone(KST).toInstant();
            Instant end = endDate.plusDays(1).atStartOfDay(KST).toInstant();

            matchedNews = deduplicateRelatedNews(findRelatedNews(symbol, start, end).stream()
                    .filter(news -> articleContentExtractor.isUsable(news.getTitle(), news.getRawContent()))
                    .filter(news -> relevanceRank(symbol, news) > 0)
                    .sorted(Comparator.comparingInt((News news) -> relevanceRank(symbol, news)).reversed()
                            .thenComparing(News::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList());
        }

        List<NewsMarkerView> markers = matchedNews.stream().map(news -> toMarker(symbol, news)).toList();
        List<ChartResponse.MoveInsightView> moveInsights = candleResult.fallback()
                ? List.of()
                : findDailyMoveInsights(symbol, candleViews, matchedNews, periodDivCode);
        ChartResponse.DocentView docent = moveInsights.stream()
                .filter(move -> !move.candidateNews().isEmpty() && "DIRECT".equals(move.candidateNews().get(0).relationType()))
                .map(ChartResponse.MoveInsightView::newsId)
                .filter(id -> id != null)
                .findFirst()
                .flatMap(id -> matchedNews.stream().filter(news -> id.equals(news.getId())).findFirst())
                .map(this::buildDocent)
                .orElse(null);

        return new ChartResponse(symbol, price, changePercent, candleViews, markers, markers, docent,
                periodDivCode, null, List.of(), candleResult.fallback(),
                moveInsights);
    }

    private List<ChartResponse.MoveInsightView> findDailyMoveInsights(
            String symbol, List<CandleView> candles, List<News> relatedNews, String period) {
        if (candles.size() < 2) {
            return List.of();
        }
        Instant collectionStart = newsRepository.collectionStart();
        List<ChartResponse.MoveInsightView> insights = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            CandleView previous = candles.get(i - 1);
            CandleView current = candles.get(i);
            if (previous.close() == 0.0) {
                continue;
            }
            double change = (current.close() - previous.close()) / previous.close() * 100.0;
            List<Double> previousChanges = new ArrayList<>();
            for (int j = Math.max(1, i - VOLATILITY_WINDOW); j < i; j++) {
                CandleView before = candles.get(j - 1);
                CandleView prior = candles.get(j);
                if (before.close() != 0.0) {
                    previousChanges.add((prior.close() - before.close()) / before.close() * 100.0);
                }
            }
            if (Math.abs(change) < ("W".equals(period) ? 5.0 : 3.0) && !isSignificantMove(previousChanges, change)) {
                continue;
            }
            var day = current.date();
            var monday = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            Instant from = "W".equals(period) ? monday.atStartOfDay(KST).toInstant()
                : com.finsight.judgement.KoreanTradingCalendar.previous(day).atTime(15, 30).atZone(KST).toInstant();
            Instant to = "W".equals(period) ? monday.plusDays(7).atStartOfDay(KST).toInstant()
                : day.atTime(15, 30).atZone(KST).toInstant();
            List<News> candidates = candidateNews(symbol, relatedNews, from, to);
            Optional<News> news = candidates.stream().findFirst();
            double causeScore = news.map(item -> causeScore(item, change)).orElse(0.0);
            String explanation = news.map(item -> (relevanceRank(symbol, item) == 1 ? "시장 전체 흐름 · " : "") + buildCauseExplanation(item, change, causeScore))
                .orElse(collectionStart != null && to.isBefore(collectionStart) ? "뉴스 수집 이전 구간" : "이 시점에 수집된 관련 뉴스가 없어요");
            insights.add(new ChartResponse.MoveInsightView(
                    current.date().atStartOfDay(),
                    Math.round(change * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    explanation,
                    causeScore,
                    candidates.stream().map(item -> toMarker(symbol, item)).toList()
            ));
            log.info("Move cause assessment: date={}, change={}%, newsId={}, score={}",
                    current.date(), Math.round(change * 100.0) / 100.0, news.map(News::getId).orElse(null), causeScore);
        }
        return insights.stream()
                .sorted(Comparator.comparingDouble((ChartResponse.MoveInsightView item) -> Math.abs(item.changePercent())).reversed())
                .limit(5)
                .toList();
    }

    private List<ChartResponse.MoveInsightView> findMoveInsights(String symbol, List<com.finsight.external.kis.KisMinuteCandle> candles) {
        if (candles.size() < 2) {
            return List.of();
        }
        LocalDate startDate = candles.get(0).timestamp().toLocalDate();
        LocalDate endDate = candles.get(candles.size() - 1).timestamp().toLocalDate();
        List<News> relatedNews = findRelatedNews(
                symbol,
                startDate.atStartOfDay(KST).toInstant(),
                endDate.plusDays(1).atStartOfDay(KST).toInstant()).stream()
                .filter(news -> articleContentExtractor.isUsable(news.getTitle(), news.getRawContent()))
                .toList();

        List<ChartResponse.MoveInsightView> insights = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            var previous = candles.get(i - 1);
            var current = candles.get(i);
            if (previous.close() == 0.0) {
                continue;
            }
            double change = (current.close() - previous.close()) / previous.close() * 100.0;
            if (Math.abs(change) < 0.5) {
                continue;
            }
            LocalDate currentDate = current.timestamp().toLocalDate();
            List<News> candidates = candidateNews(symbol, relatedNews,
                com.finsight.judgement.KoreanTradingCalendar.previous(currentDate).atTime(15, 30).atZone(KST).toInstant(),
                current.timestamp().atZone(KST).toInstant());
            Optional<News> news = candidates.stream().findFirst();
            double causeScore = news.map(item -> causeScore(item, change)).orElse(0.0);
            String explanation = news.map(item -> buildCauseExplanation(item, change, causeScore)).orElse(null);
            insights.add(new ChartResponse.MoveInsightView(
                    current.timestamp(),
                    Math.round(change * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    explanation,
                    causeScore,
                    candidates.stream().map(item -> toMarker(symbol, item)).toList()
            ));
            log.info("Move cause assessment: timestamp={}, change={}%, newsId={}, score={}",
                    current.timestamp(), Math.round(change * 100.0) / 100.0, news.map(News::getId).orElse(null), causeScore);
        }
        return insights.stream()
                .sorted(Comparator.comparingDouble((ChartResponse.MoveInsightView item) -> Math.abs(item.changePercent())).reversed())
                .limit(5)
                .toList();
    }

    private ChartResponse.DocentView buildDocent(News news) {
        String whatHappened = news.getRewrittenNormal() != null ? news.getRewrittenNormal() : news.getRawContent();
        if (whatHappened != null && whatHappened.length() > 600) whatHappened = whatHappened.substring(0, 600) + "…";
        return new ChartResponse.DocentView(news.getId(), news.getTitle(), news.getSource(), whatHappened, news.getImportanceReason());
    }

    static List<News> deduplicateRelatedNews(List<News> news) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<News> result = new ArrayList<>();
        for (News item : news) {
            String title = normalize(item.getTitle());
            String content = normalize(item.getRawContent());
            String url = normalize(item.getUrl());
            List<String> keys = new ArrayList<>();
            if (!url.isBlank()) keys.add("url:" + url);
            if (!content.isBlank()) keys.add("content:" + content);
            if (!title.isBlank() && item.getPublishedAt() != null) {
                keys.add("meta:" + title + "|" + normalize(item.getSource()) + "|" + item.getPublishedAt());
            }
            if (keys.stream().noneMatch(seen::contains)) {
                seen.addAll(keys);
                result.add(item);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    static News latestNewsBefore(List<News> news, Instant moveAt) {
        return news.stream()
                .filter(item -> item.getPublishedAt() != null && item.getPublishedAt().isBefore(moveAt))
                .max(Comparator.comparing(News::getPublishedAt))
                .orElse(null);
    }

    static boolean isSignificantMove(List<Double> previousChanges, double changePercent) {
        if (previousChanges.size() < MIN_VOLATILITY_OBSERVATIONS) return false;
        double mean = previousChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = previousChanges.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average().orElse(0.0);
        double standardDeviation = Math.sqrt(variance);
        return standardDeviation > 0.0 && Math.abs(changePercent) >= VOLATILITY_MULTIPLIER * standardDeviation;
    }

    private String buildCauseExplanation(News news, double changePercent, double causeScore) {
        SentimentHint sentiment = news.getSentimentHint();
        boolean directionMatches = (changePercent > 0 && sentiment == SentimentHint.POSITIVE)
                || (changePercent < 0 && sentiment == SentimentHint.NEGATIVE);
        if (directionMatches) {
            if (news.getRelatedSymbol() != null && news.getRelatedSymbol().startsWith("KOS")) {
                return "시장 방향과 변동 시점이 겹치는 관련 뉴스입니다. 개별 종목의 직접 원인으로 단정하지 않습니다.";
            }
            return "주가 방향과 시점이 겹친 기사: " + news.getTitle()
                    + ". 근거: " + firstNonBlank(news.getImportanceReason(), "뉴스 방향과 주가 방향이 일치합니다.")
                    + " 가격 변동의 직접 원인으로 단정하지 않습니다.";
        }
        if (sentiment == SentimentHint.NEUTRAL) {
            return "관련 뉴스는 확인됐지만 방향성 근거가 부족해 원인으로 단정하지 않습니다.";
        }
        return "관련 뉴스는 확인됐지만 뉴스 방향과 주가 방향이 달라 직접 원인으로 보기 어렵습니다.";
    }

    private double causeScore(News news, double changePercent) {
        if (news.getSentimentHint() == null) {
            return 0.0;
        }
        boolean marketNews = news.getRelatedSymbol() != null && news.getRelatedSymbol().startsWith("KOS");
        boolean directionMatches = (changePercent > 0 && news.getSentimentHint() == SentimentHint.POSITIVE)
                || (changePercent < 0 && news.getSentimentHint() == SentimentHint.NEGATIVE);
        if (directionMatches) {
            return marketNews ? 0.6 : 0.8;
        }
        return news.getSentimentHint() == SentimentHint.NEUTRAL
                ? (marketNews ? 0.3 : 0.4)
                : 0.1;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // % change vs the previous trading day's close — this was previously
    // missing from the response entirely, so the frontend always defaulted
    // it to a hardcoded 0.00%.
    private double computeChangePercent(List<CandleView> candleViews) {
        if (candleViews.size() < 2) {
            return 0.0;
        }
        double latestClose = candleViews.get(candleViews.size() - 1).close();
        double previousClose = candleViews.get(candleViews.size() - 2).close();
        if (previousClose == 0.0) {
            return 0.0;
        }
        return Math.round((latestClose - previousClose) / previousClose * 1000.0) / 10.0;
    }

    private double computeMinuteChangePercent(List<ChartResponse.MinuteCandleView> candles) {
        if (candles.size() < 2) {
            return 0.0;
        }
        double previous = candles.get(candles.size() - 2).close();
        if (previous == 0.0) {
            return 0.0;
        }
        double latest = candles.get(candles.size() - 1).close();
        return Math.round((latest - previous) / previous * 1000.0) / 10.0;
    }

    private NewsMarkerView toMarker(String symbol, News news) {
        LocalDate date = news.getPublishedAt() == null
                ? null
                : news.getPublishedAt().atZone(KST).toLocalDate();
        return new NewsMarkerView(date, news.getPublishedAt(), news.getId(), news.getTitle(), news.getSource(), relevanceRank(symbol, news) > 1 ? "DIRECT" : "MARKET");
    }

    private List<News> findRelatedNews(String symbol, Instant start, Instant end) {
        return newsRepository.findByPublishedAtBetween(start, end).stream()
            .filter(news -> relevanceRank(symbol, news) > 0).toList();
    }

    static int relevanceRank(String symbol, News news) {
        var company = com.finsight.localml.ChartCompanyMatcher.get(symbol);
        String title = news.getTitle() == null ? "" : news.getTitle();
        if (company != null && company.pattern().matcher(title).find()) return 3;
        if (title.matches(".*(美|미국|뉴욕|나스닥|S&P|해외).*")) return 0;
        if (company != null && company.pattern().matcher(com.finsight.localml.ChartCompanyMatcher.cleanBody(news.getRawContent())).results().count() >= 2) return 2;
        if (company == null && symbol.equals(new NewsSymbolMatcher().match(title, news.getRawContent()))) return 3;
        return title.matches(".*(코스피|코스닥|국내 증시|한국 증시).*" ) ? 1 : 0;
    }

    static List<News> candidateNews(String symbol, List<News> news, Instant from, Instant to) {
        return news.stream().filter(item -> item.getPublishedAt() != null
                && !item.getPublishedAt().isBefore(from) && !item.getPublishedAt().isAfter(to)
                && relevanceRank(symbol, item) > 0)
            .sorted(Comparator.comparingInt((News item) -> relevanceRank(symbol, item)).reversed()
                .thenComparing(News::getPublishedAt, Comparator.reverseOrder()))
            .limit(3).toList();
    }
}
