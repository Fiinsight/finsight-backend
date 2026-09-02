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
    private static final int DEFAULT_CANDLE_COUNT = 30;

    private final KisDailyCandleClient kisDailyCandleClient;
    private final NewsRepository newsRepository;

    public ChartService(KisDailyCandleClient kisDailyCandleClient, NewsRepository newsRepository) {
        this.kisDailyCandleClient = kisDailyCandleClient;
        this.newsRepository = newsRepository;
    }

    public ChartResponse getChart(String symbol) {
        List<KisDailyCandle> candles = kisDailyCandleClient.getDailyCandles(symbol, DEFAULT_CANDLE_COUNT);

        List<CandleView> candleViews = candles.stream()
                .sorted(Comparator.comparing(KisDailyCandle::date))
                .map(c -> new CandleView(c.date(), c.open(), c.high(), c.low(), c.close()))
                .toList();

        List<NewsMarkerView> markers;
        if (candleViews.isEmpty()) {
            markers = List.of();
        } else {
            LocalDate startDate = candleViews.get(0).date();
            LocalDate endDate = candleViews.get(candleViews.size() - 1).date();
            Instant start = startDate.atStartOfDay(KST).toInstant();
            Instant end = endDate.plusDays(1).atStartOfDay(KST).toInstant();

            markers = newsRepository.findByRelatedSymbolAndPublishedAtBetween(symbol, start, end).stream()
                    .map(this::toMarker)
                    .toList();
        }

        return new ChartResponse(symbol, candleViews, markers);
    }

    private NewsMarkerView toMarker(News news) {
        LocalDate date = news.getPublishedAt() == null
                ? null
                : news.getPublishedAt().atZone(KST).toLocalDate();
        return new NewsMarkerView(date, news.getId(), news.getTitle());
    }
}
