package com.finsight.external;

/**
 * A single ECOS statistic value (base rate, USD/KRW rate, ...).
 *
 * @param fallback true when this value is a hardcoded fallback because the
 *                 real ECOS API call failed or no API key is configured.
 */
public record EcosRate(String seriesName, double value, String asOfPeriod, boolean fallback) {
}
