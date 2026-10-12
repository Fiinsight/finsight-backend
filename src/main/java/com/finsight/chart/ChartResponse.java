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
    @com.fasterxml.jackson.annotation.JsonProperty("symbolName")
    public String symbolName() { return com.finsight.stock.StockCatalog.name(symbol); }

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
            double causeScore,
            List<NewsMarkerView> candidateNews,
            DocentView docent
    ) {
    }

    public record NewsMarkerView(LocalDate date, Instant publishedAt, Long newsId, String title, String source, String relationType) {
    }

    public record DocentView(String relation, String whatHappened, String possibleReason, String marketImpact,
                             Long newsId, String newsTitle, String source, String mode) {
        @com.fasterxml.jackson.annotation.JsonProperty("whyItMoved")
        public String whyItMoved() { return possibleReason; }
    }
}
