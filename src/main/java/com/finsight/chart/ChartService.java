package com.finsight.chart;

import com.finsight.chart.ChartResponse.CandleView;
import com.finsight.chart.ChartResponse.NewsMarkerView;
import com.finsight.external.kis.KisDailyCandle;
import com.finsight.external.kis.KisDailyCandleClient;
import com.finsight.external.kis.KisMinuteCandleClient;
import com.finsight.external.kis.KisMinuteCandleResult;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ChartService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DAILY_CANDLE_COUNT = 30;
    private static final int WEEKLY_CANDLE_COUNT = 7; // enough bars for a five-return baseline
    private static final int VOLATILITY_WINDOW = 20;
    private static final int MIN_VOLATILITY_OBSERVATIONS = 5;
    private static final double VOLATILITY_MULTIPLIER = 2.0;

    private final KisDailyCandleClient kisDailyCandleClient;
    private final KisMinuteCandleClient kisMinuteCandleClient;
    private final NewsRepository newsRepository;

    public ChartService(KisDailyCandleClient kisDailyCandleClient,
                        KisMinuteCandleClient kisMinuteCandleClient,
                        NewsRepository newsRepository) {
        this.kisDailyCandleClient = kisDailyCandleClient;
        this.kisMinuteCandleClient = kisMinuteCandleClient;
        this.newsRepository = newsRepository;
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

    public ChartResponse getChart(String symbol, String period, int intervalMinutes) {
        if ("MINUTE".equalsIgnoreCase(period) || "M".equalsIgnoreCase(period)) {
            int interval = intervalMinutes == 1 || intervalMinutes == 5 || intervalMinutes == 15 ? intervalMinutes : 5;
            KisMinuteCandleResult result = kisMinuteCandleClient.getCandlesWithStatus(symbol, interval, 120);
            List<ChartResponse.MinuteCandleView> minuteCandles = result.candles().stream()
                    .map(c -> new ChartResponse.MinuteCandleView(c.timestamp(), c.open(), c.high(), c.low(), c.close()))
                    .toList();
            double price = minuteCandles.isEmpty() ? 0.0 : minuteCandles.get(minuteCandles.size() - 1).close();
            double changePercent = computeMinuteChangePercent(minuteCandles);
            List<MovePoint> points = toMinuteMovePoints(result.candles());
            return new ChartResponse(symbol, price, changePercent, List.of(), List.of(), null,
                    "MINUTE", interval, minuteCandles, result.fallback(),
                    result.fallback() ? List.of() : findMoveInsights(symbol, points));
        }

        String periodDivCode = "W".equalsIgnoreCase(period) ? "W" : "D";
        int count = "W".equals(periodDivCode) ? WEEKLY_CANDLE_COUNT : DAILY_CANDLE_COUNT;
        List<KisDailyCandle> candles = kisDailyCandleClient.getCandles(symbol, count, periodDivCode);

        List<CandleView> candleViews = candles.stream()
                .sorted(Comparator.comparing(KisDailyCandle::date))
                .map(c -> new CandleView(c.date(), c.open(), c.high(), c.low(), c.close()))
                .toList();

        double price = candleViews.isEmpty() ? 0.0 : candleViews.get(candleViews.size() - 1).close();
        double changePercent = computeChangePercent(candleViews);
        List<MovePoint> points = toDailyMovePoints(candles);

        List<News> matchedNews;
        if (candleViews.isEmpty()) {
            matchedNews = List.of();
        } else {
            LocalDate startDate = candleViews.get(0).date();
            LocalDate endDate = candleViews.get(candleViews.size() - 1).date();
            Instant start = startDate.atStartOfDay(KST).toInstant();
            Instant end = endDate.plusDays(1).atStartOfDay(KST).toInstant();

            matchedNews = deduplicateRelatedNews(newsRepository.findByRelatedSymbolAndPublishedAtBetween(symbol, start, end).stream()
                    .sorted(Comparator.comparing(News::getPublishedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList());
        }

        List<NewsMarkerView> markers = matchedNews.stream().map(this::toMarker).toList();
        List<ChartResponse.MoveInsightView> moveInsights = findMoveInsights(symbol, points);
        ChartResponse.DocentView docent = moveInsights.stream()
                .map(ChartResponse.MoveInsightView::newsId)
                .filter(id -> id != null)
                .findFirst()
                .flatMap(id -> matchedNews.stream().filter(news -> id.equals(news.getId())).findFirst())
                .map(this::buildDocent)
                .orElse(null);

        return new ChartResponse(symbol, price, changePercent, candleViews, markers, docent,
                periodDivCode, null, List.of(), false, moveInsights);
    }

    static List<News> deduplicateRelatedNews(List<News> news) {
        Set<String> seen = new HashSet<>();
        List<News> result = new ArrayList<>();
        for (News item : news) {
            String titleKey = normalize(item.getTitle());
            String contentKey = normalize(item.getRawContent());
            List<String> keys = new ArrayList<>();
            String urlKey = normalize(item.getUrl());
            if (!urlKey.isBlank()) {
                keys.add("url:" + urlKey);
            }
            if (!contentKey.isBlank()) {
                keys.add("content:" + contentKey);
            }
            if (!titleKey.isBlank() && item.getPublishedAt() != null) {
                keys.add("meta:" + titleKey + "|" + normalize(item.getSource()) + "|" + item.getPublishedAt());
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

    private List<ChartResponse.MoveInsightView> findMoveInsights(String symbol, List<MovePoint> points) {
        if (points.size() < MIN_VOLATILITY_OBSERVATIONS + 1) {
            return List.of();
        }
        LocalDate startDate = points.get(0).timestamp().toLocalDate();
        LocalDate endDate = points.get(points.size() - 1).timestamp().toLocalDate();
        List<News> relatedNews = newsRepository.findByRelatedSymbolAndPublishedAtBetween(
                symbol,
                startDate.atStartOfDay(KST).toInstant(),
                endDate.plusDays(1).atStartOfDay(KST).toInstant());

        List<ChartResponse.MoveInsightView> insights = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            MovePoint current = points.get(i);
            int from = Math.max(0, i - VOLATILITY_WINDOW);
            List<Double> previousChanges = points.subList(from, i).stream()
                    .map(MovePoint::changePercent)
                    .toList();
            if (!isSignificantMove(previousChanges, current.changePercent())) {
                continue;
            }
            Optional<News> news = Optional.ofNullable(latestNewsBefore(relatedNews,
                    current.timestamp().atZone(KST).toInstant()));
            insights.add(new ChartResponse.MoveInsightView(
                    current.timestamp(),
                    Math.round(current.changePercent() * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    news.map(item -> "관련 뉴스: " + item.getImportanceReason()).orElse(null)
            ));
        }
        return insights.stream()
                .sorted(Comparator.comparingDouble((ChartResponse.MoveInsightView item) -> Math.abs(item.changePercent())).reversed())
                .limit(3)
                .toList();
    }

    static News latestNewsBefore(List<News> news, Instant moveAt) {
        return news.stream()
                .filter(item -> item.getPublishedAt() != null && item.getPublishedAt().isBefore(moveAt))
                .max(Comparator.comparing(News::getPublishedAt))
                .orElse(null);
    }

    static boolean isSignificantMove(List<Double> previousChanges, double changePercent) {
        if (previousChanges.size() < MIN_VOLATILITY_OBSERVATIONS) {
            return false;
        }
        double mean = previousChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = previousChanges.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0.0);
        double standardDeviation = Math.sqrt(variance);
        return standardDeviation > 0.0
                && Math.abs(changePercent) >= VOLATILITY_MULTIPLIER * standardDeviation;
    }

    private List<MovePoint> toDailyMovePoints(List<KisDailyCandle> candles) {
        List<KisDailyCandle> sorted = candles.stream()
                .sorted(Comparator.comparing(KisDailyCandle::date))
                .toList();
        List<MovePoint> points = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            KisDailyCandle previous = sorted.get(i - 1);
            KisDailyCandle current = sorted.get(i);
            if (previous.close() != 0.0) {
                points.add(new MovePoint(current.date().atStartOfDay(),
                        (current.close() - previous.close()) / previous.close() * 100.0));
            }
        }
        return points;
    }

    private List<MovePoint> toMinuteMovePoints(List<com.finsight.external.kis.KisMinuteCandle> candles) {
        List<com.finsight.external.kis.KisMinuteCandle> sorted = candles.stream()
                .sorted(Comparator.comparing(com.finsight.external.kis.KisMinuteCandle::timestamp))
                .toList();
        List<MovePoint> points = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            var previous = sorted.get(i - 1);
            var current = sorted.get(i);
            if (previous.close() != 0.0) {
                points.add(new MovePoint(current.timestamp(),
                        (current.close() - previous.close()) / previous.close() * 100.0));
            }
        }
        return points;
    }

    private record MovePoint(LocalDateTime timestamp, double changePercent) { }

    private ChartResponse.DocentView buildDocent(News news) {
        String whatHappened = news.getRewrittenNormal() != null ? news.getRewrittenNormal() : news.getRawContent();
        String reason = "시장 방향과 변동 시점이 겹치는 관련 뉴스입니다. 개별 종목의 직접 원인으로 단정하지 않습니다.";
        if (news.getImportanceReason() != null && !news.getImportanceReason().isBlank()) {
            reason += " " + news.getImportanceReason();
        }
        return new ChartResponse.DocentView(news.getId(), news.getTitle(), news.getSource(), whatHappened, reason);
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

    private NewsMarkerView toMarker(News news) {
        LocalDate date = news.getPublishedAt() == null
                ? null
                : news.getPublishedAt().atZone(KST).toLocalDate();
        return new NewsMarkerView(date, news.getId(), news.getTitle(), news.getSource());
    }
}
