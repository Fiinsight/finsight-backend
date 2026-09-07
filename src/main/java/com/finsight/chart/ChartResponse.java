package com.finsight.chart;

import java.time.LocalDate;
import java.util.List;

public record ChartResponse(
        String symbol,
        double price,
        double changePercent,
        List<CandleView> candles,
        List<NewsMarkerView> newsMarkers,
        DocentView docent
) {
    public record CandleView(LocalDate date, double open, double high, double low, double close) {
    }

    public record NewsMarkerView(LocalDate date, Long newsId, String title, String source) {
    }

    // Grounded in the most recently published news actually tagged with this
    // symbol — reuses that article's already-AI-generated rewrite/importance
    // text rather than inventing new copy, so this is null (not a fake
    // placeholder) when no matching news exists yet.
    public record DocentView(Long newsId, String newsTitle, String source, String whatHappened, String whyItMoved) {
    }
}
