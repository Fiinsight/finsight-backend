package com.finsight.market;

public record MarketSummaryResponse(
        MarketIndexView kospi,
        MarketIndexView kosdaq,
        RateView baseRate,
        RateView usdKrwRate
) {
    public record MarketIndexView(double currentValue, double changePercent, boolean fallback) {
    }

    public record RateView(double value, String asOfPeriod, boolean fallback) {
    }
}
