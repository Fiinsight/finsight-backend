package com.finsight.chart;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

public record ChartResponse(
        String symbol,
        double price,
        double changePercent,
        List<CandleView> candles,
        List<NewsMarkerView> newsMarkers,
        List<NewsMarkerView> relatedNews,
        DocentView docent,
        String period,
        Integer intervalMinutes,
        List<MinuteCandleView> minuteCandles,
        boolean fallback,
        List<MoveInsightView> moveInsights
) {
    public record CandleView(LocalDate date, double open, double high, double low, double close) {
    }

    public record MinuteCandleView(LocalDateTime timestamp, double open, double high, double low, double close) {
    }

    public record MoveInsightView(
            LocalDateTime timestamp,
            double changePercent,
            Long newsId,
            String newsTitle,
            String newsSource,
            String explanation,
            double causeScore
    ) {
    }

    public record NewsMarkerView(LocalDate date, Instant publishedAt, Long newsId, String title, String source) {
    }

    // Grounded in the most recently published news actually tagged with this
    // symbol — reuses that article's already-AI-generated rewrite/importance
    // text rather than inventing new copy, so this is null (not a fake
    // placeholder) when no matching news exists yet.
    public record DocentView(Long newsId, String newsTitle, String source, String whatHappened, String whyItMoved) {
    }
}
