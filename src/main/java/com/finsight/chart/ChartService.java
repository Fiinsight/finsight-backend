package com.finsight.chart;

import com.finsight.chart.ChartResponse.CandleView;
import com.finsight.chart.ChartResponse.NewsMarkerView;
import com.finsight.external.kis.KisDailyCandle;
import com.finsight.external.kis.KisDailyCandleClient;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChartService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DAILY_CANDLE_COUNT = 30;
    private static final int WEEKLY_CANDLE_COUNT = 6; // ~a month and a bit of weekly bars

    private final KisDailyCandleClient kisDailyCandleClient;
    private final NewsRepository newsRepository;

    public ChartService(KisDailyCandleClient kisDailyCandleClient, NewsRepository newsRepository) {
        this.kisDailyCandleClient = kisDailyCandleClient;
        this.newsRepository = newsRepository;
    }

    public ChartResponse getChart(String symbol) {
        return getChart(symbol, "D");
    }

    /**
     * @param period "D"(일봉) or "W"(주봉)
     */
    public ChartResponse getChart(String symbol, String period) {
        String periodDivCode = "W".equalsIgnoreCase(period) ? "W" : "D";
        int count = "W".equals(periodDivCode) ? WEEKLY_CANDLE_COUNT : DAILY_CANDLE_COUNT;
        List<KisDailyCandle> candles = kisDailyCandleClient.getCandles(symbol, count, periodDivCode);

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
            Instant start = startDate.atStartOfDay(KST).toInstant();
            Instant end = endDate.plusDays(1).atStartOfDay(KST).toInstant();

            matchedNews = newsRepository.findByRelatedSymbolAndPublishedAtBetween(symbol, start, end).stream()
                    .sorted(Comparator.comparing(News::getPublishedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }

        List<NewsMarkerView> markers = matchedNews.stream().map(this::toMarker).toList();
        ChartResponse.DocentView docent = matchedNews.isEmpty() ? null : buildDocent(matchedNews.get(0));

        return new ChartResponse(symbol, price, changePercent, candleViews, markers, docent);
    }

    private ChartResponse.DocentView buildDocent(News news) {
        String whatHappened = news.getRewrittenNormal() != null ? news.getRewrittenNormal() : news.getRawContent();
        return new ChartResponse.DocentView(news.getId(), news.getTitle(), news.getSource(), whatHappened, news.getImportanceReason());
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

    private NewsMarkerView toMarker(News news) {
        LocalDate date = news.getPublishedAt() == null
                ? null
                : news.getPublishedAt().atZone(KST).toLocalDate();
        return new NewsMarkerView(date, news.getId(), news.getTitle(), news.getSource());
    }
}
