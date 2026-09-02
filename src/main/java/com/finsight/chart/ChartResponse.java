package com.finsight.chart;

import java.time.LocalDate;
import java.util.List;

public record ChartResponse(
        String symbol,
        List<CandleView> candles,
        List<NewsMarkerView> newsMarkers
) {
    public record CandleView(LocalDate date, double open, double high, double low, double close) {
    }

    public record NewsMarkerView(LocalDate date, Long newsId, String title) {
    }
}
