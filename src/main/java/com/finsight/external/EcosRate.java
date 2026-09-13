package com.finsight.external;

/**
 * A single ECOS statistic value (base rate, USD/KRW rate, ...).
 *
 * @param changePercent % change vs. the previous period in the same query
 *                       window (previous day for the daily USD/KRW series,
 *                       previous month for the monthly base rate). Null when
 *                       there isn't a previous row to compare against.
 * @param fallback true when this value is a hardcoded fallback because the
 *                 real ECOS API call failed or no API key is configured.
 */
public record EcosRate(String seriesName, double value, String asOfPeriod, Double changePercent, boolean fallback) {
}
