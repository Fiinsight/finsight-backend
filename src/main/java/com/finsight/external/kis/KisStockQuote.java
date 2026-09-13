package com.finsight.external.kis;

/**
 * Current price / change for a single stock symbol.
 *
 * @param fallback true when this value is a hardcoded fallback because the
 *                 real KIS API call failed or no API key is configured.
 */
public record KisStockQuote(String stockCode, double currentPrice, double changePercent, boolean fallback) {
}
