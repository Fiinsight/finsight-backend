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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ChartDocentService docentService;

    private com.finsight.external.kis.KisStockQuote cachedQuote(String symbol) {
        if (cacheManager != null) {
            var popular = cacheManager.getCache("popularStockQuotes");
            var all = popular == null ? null : popular.get("all");
            if (all != null && all.get() instanceof List<?> stocks) {
                for (var item : stocks) if (item instanceof com.finsight.stock.PopularStockView stock && symbol.equals(stock.symbol()))
                    return new com.finsight.external.kis.KisStockQuote(symbol, stock.price(), stock.changePercent(), stock.fallback());
            }
            var quotes = cacheManager.getCache("kisStockQuotes");
            var quote = quotes == null ? null : quotes.get(symbol, com.finsight.external.kis.KisStockQuote.class);
            if (quote != null) return quote;
        }
        return new com.finsight.external.kis.KisStockQuote(symbol, 0, 0, true);
    }

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

    public ChartResponse withCurrentQuote(ChartResponse chart) {
        var quote = cachedQuote(chart.symbol());
        return new ChartResponse(chart.symbol(), quote.currentPrice(), quote.changePercent(), chart.candles(), chart.newsMarkers(), chart.relatedNews(), chart.docent(), chart.period(), chart.intervalMinutes(), chart.minuteCandles(), chart.fallback(), chart.moveInsights());
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

    @Cacheable(cacheNames = "chartResponses", key = "#symbol + ' : ' + #period.toUpperCase() + ' : ' + ((#period.equalsIgnoreCase('MINUTE') || #period.equalsIgnoreCase('M')) ? #intervalMinutes : 0)", sync = true)
    public ChartResponse getChart(String symbol, String period, int intervalMinutes) {
        var quote = cachedQuote(symbol);
        if ("MINUTE".equalsIgnoreCase(period) || "M".equalsIgnoreCase(period)) {
            int interval = intervalMinutes == 1 || intervalMinutes == 5 || intervalMinutes == 15 ? intervalMinutes : 5;
            KisMinuteCandleResult result = kisMinuteCandleClient.getCandlesWithStatus(symbol, interval, 120);
            List<ChartResponse.MinuteCandleView> minuteCandles = result.candles().stream()
                    .map(c -> new ChartResponse.MinuteCandleView(c.timestamp(), c.open(), c.high(), c.low(), c.close()))
                    .toList();
            double price = quote.currentPrice();
            double changePercent = quote.changePercent();
            var moves=result.fallback()?List.<ChartResponse.MoveInsightView>of():findMoveInsights(symbol,result.candles());
            return new ChartResponse(symbol, price, changePercent, List.of(), List.of(), List.of(), largestDocent(moves),
                    "MINUTE", interval, minuteCandles, result.fallback(),
                    moves);
        }

        String periodDivCode = "W".equalsIgnoreCase(period) ? "W" : "D";
        int count = "W".equals(periodDivCode) ? WEEKLY_CANDLE_COUNT : DAILY_CANDLE_COUNT;
        KisDailyCandleResult candleResult = kisDailyCandleClient.getCandlesWithStatus(symbol, count, periodDivCode);
        List<KisDailyCandle> candles = candleResult.candles();

        List<CandleView> candleViews = candles.stream()
                .sorted(Comparator.comparing(KisDailyCandle::date))
                .map(c -> new CandleView(c.date(), c.open(), c.high(), c.low(), c.close()))
                .toList();

        double price = quote.currentPrice();
        double changePercent = quote.changePercent();

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
                    .sorted(Comparator.comparing(News::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList());
        }

        List<NewsMarkerView> markers = matchedNews.stream().map(news -> toMarker(symbol, news)).toList();
        List<ChartResponse.MoveInsightView> moveInsights = candleResult.fallback()
                ? List.of()
                : findDailyMoveInsights(symbol, candleViews, matchedNews, periodDivCode);
        ChartResponse.DocentView docent = largestDocent(moveInsights);

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
            var docent = ChartDocentService.rule(docentRequest(symbol, current.date().atStartOfDay(), change, period, candidates, "normal"));
            String explanation = news.isEmpty() && collectionStart != null && to.atZone(KST).toLocalDate().isBefore(collectionStart.atZone(KST).toLocalDate())
                    ? "뉴스 수집(" + collectionStart.atZone(KST).format(java.time.format.DateTimeFormatter.ofPattern("M/d")) + ") 이전 구간이에요" : docent.possibleReason();
            insights.add(new ChartResponse.MoveInsightView(
                    current.date().atStartOfDay(),
                    Math.round(change * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    explanation,
                    causeScore,
                    candidates.stream().map(item -> toMarker(symbol, item)).toList(),
                    docent
            ));
            log.info("Move cause assessment: date={}, change={}%, newsId={}, score={}",
                    current.date(), Math.round(change * 100.0) / 100.0, news.map(News::getId).orElse(null), causeScore);
        }
        return insights.stream()
                .sorted(Comparator.comparing((ChartResponse.MoveInsightView item) -> collectionStart != null
                    && ("W".equals(period) ? item.timestamp().toLocalDate().plusDays(6) : item.timestamp().toLocalDate())
                        .isBefore(collectionStart.atZone(KST).toLocalDate()))
                    .thenComparing(Comparator.comparingDouble((ChartResponse.MoveInsightView item) -> Math.abs(item.changePercent())).reversed()))
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
            var docent = ChartDocentService.rule(docentRequest(symbol, current.timestamp(), change, "MINUTE", candidates, "normal"));
            String explanation = docent.possibleReason();
            insights.add(new ChartResponse.MoveInsightView(
                    current.timestamp(),
                    Math.round(change * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    explanation,
                    causeScore,
                    candidates.stream().map(item -> toMarker(symbol, item)).toList(),
                    docent
            ));
            log.info("Move cause assessment: timestamp={}, change={}%, newsId={}, score={}",
                    current.timestamp(), Math.round(change * 100.0) / 100.0, news.map(News::getId).orElse(null), causeScore);
        }
        return insights.stream()
                .sorted(Comparator.comparingDouble((ChartResponse.MoveInsightView item) -> Math.abs(item.changePercent())).reversed())
                .limit(5)
                .toList();
    }

    static ChartResponse.DocentView largestDocent(List<ChartResponse.MoveInsightView> moves) {
        return moves.stream().max(Comparator.comparingDouble(move -> Math.abs(move.changePercent()))).map(ChartResponse.MoveInsightView::docent).orElse(null);
    }
    private com.finsight.external.AiDocentRequest docentRequest(String symbol, LocalDateTime at, double change, String period, List<News> candidates, String level) {
        String index="KOSDAQ".equals(com.finsight.stock.StockCatalog.market(symbol))?"코스닥":"코스피";
        Double indexChange=null;
        if ("D".equals(period) && cacheManager != null) {
            indexChange = cachedIndexChange(cacheManager.getCache("kisDailyCandles"), "코스닥".equals(index) ? "KOSDAQ" : "KOSPI", at.toLocalDate());
        }
        var news=candidates.stream().limit(3).map(item -> {
            String body=articleContentExtractor.clean(item.getTitle(),item.getRawContent());
            String summary=docentSummary(body);
            return new com.finsight.external.AiDocentRequest.Article(item.getId(),item.getTitle(),summary,item.getPublishedAt(),relevanceRank(symbol,item)>1?"DIRECT":"MARKET",item.getSource());
        }).toList();
        return new com.finsight.external.AiDocentRequest(symbol,com.finsight.stock.StockCatalog.name(symbol),at.toString().length()==16?at.toString()+":00":at.toString(),change,period,news,level,index,indexChange);
    }
    static Double cachedIndexChange(org.springframework.cache.Cache cache, String symbol, LocalDate day) {
        if (cache == null) return null;
        List<KisDailyCandleResult> results = new ArrayList<>();
        var recent = cache.get(symbol + ":30:D");
        if (recent != null && recent.get() instanceof KisDailyCandleResult data) results.add(data);
        Object nativeCache = cache.getNativeCache();
        java.util.Map<?, ?> entries = nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine
                ? caffeine.asMap() : nativeCache instanceof java.util.Map<?, ?> map ? map : java.util.Map.of();
        entries.forEach((key, value) -> {
            if (key instanceof String text && text.startsWith("close:" + symbol + ":") && value instanceof KisDailyCandleResult data) results.add(data);
        });
        for (var data : results) {
            if (data.fallback()) continue;
            var sorted = data.candles().stream().sorted(Comparator.comparing(KisDailyCandle::date)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).date().equals(day) && sorted.get(i - 1).date().equals(com.finsight.judgement.KoreanTradingCalendar.previous(day)) && sorted.get(i - 1).close() > 0)
                    return Math.round((sorted.get(i).close() / sorted.get(i - 1).close() - 1) * 10000.0) / 100.0;
            }
        }
        return null;
    }
    static String docentSummary(String body) {
        if (body.length() <= 400) return body;
        StringBuilder summary = new StringBuilder();
        for (String sentence : body.split("(?<=[.!?])\\s+|\\n+")) {
            if (summary.length() + sentence.length() + 1 > 400) break;
            if (!summary.isEmpty()) summary.append(' ');
            summary.append(sentence);
        }
        return summary.toString();
    }
    public ChartResponse withDocents(ChartResponse chart,String level) {
        if (docentService==null) return chart;
        var moves=chart.moveInsights().stream().map(move -> {
            var articles=move.candidateNews().stream().map(marker -> newsRepository.findById(marker.newsId()).orElse(null)).filter(java.util.Objects::nonNull).toList();
            var docent=docentService.get(docentRequest(chart.symbol(),move.timestamp(),move.changePercent(),chart.period(),articles,level));
            return new ChartResponse.MoveInsightView(move.timestamp(),move.changePercent(),move.newsId(),move.newsTitle(),move.newsSource(),docent.possibleReason(),move.causeScore(),move.candidateNews(),docent);
        }).toList();
        return new ChartResponse(chart.symbol(),chart.price(),chart.changePercent(),chart.candles(),chart.newsMarkers(),chart.relatedNews(),largestDocent(moves),chart.period(),chart.intervalMinutes(),chart.minuteCandles(),chart.fallback(),moves);
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
        return com.finsight.news.collect.NewsDeduplicator.distinctArticles(result);
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

    static SentimentHint effectiveSentiment(News news) {
        var title=ChartDocentService.titleSentiment(news.getTitle());
        return news.getSentimentHint()==null || news.getSentimentHint()==SentimentHint.NEUTRAL ? (title==null?SentimentHint.NEUTRAL:title) : news.getSentimentHint();
    }
    private double causeScore(News news, double changePercent) {
        SentimentHint sentiment = effectiveSentiment(news);
        if (sentiment == null) {
            return 0.0;
        }
        boolean marketNews = news.getRelatedSymbol() != null && news.getRelatedSymbol().startsWith("KOS");
        boolean directionMatches = (changePercent > 0 && sentiment == SentimentHint.POSITIVE)
                || (changePercent < 0 && sentiment == SentimentHint.NEGATIVE);
        if (directionMatches) {
            return marketNews ? 0.6 : 0.8;
        }
        return sentiment == SentimentHint.NEUTRAL
                ? (marketNews ? 0.3 : 0.4)
                : 0.1;
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
        String title = news.getTitle() == null ? "" : news.getTitle();
        String raw = com.finsight.localml.ChartCompanyMatcher.cleanBody(news.getRawContent());
        String lead = raw.substring(0, Math.min(300, raw.length()));
        String name = com.finsight.stock.StockCatalog.name(symbol);
        if (symbol.equals(news.getRelatedSymbol()) || title.contains(symbol) || lead.contains(symbol)
                || !name.equals(symbol) && (title.contains(name) || lead.contains(name))) return 3;
        String market = com.finsight.stock.StockCatalog.market(symbol);
        String index = "KOSDAQ".equals(market) ? "코스닥" : "KOSPI".equals(market) ? "코스피" : null;
        String other = "코스피".equals(index) ? "코스닥" : "코스피";
        if (index == null || title.contains(other) && title.matches(".*(IPO|ETF|상장|상장심사|분배금).*")) return 0;
        return title.contains(index) ? 1 : 0;
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
