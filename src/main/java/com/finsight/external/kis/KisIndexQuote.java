package com.finsight.external.kis;

/**
 * Current value / change for a market index (e.g. KOSPI, KOSDAQ).
 *
 * @param fallback true when this value is a hardcoded fallback because the
 *                 real KIS API call failed or no API key is configured.
 */
public record KisIndexQuote(String indexCode, double currentValue, double changePercent, boolean fallback) {
}
