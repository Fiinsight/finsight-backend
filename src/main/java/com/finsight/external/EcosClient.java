package com.finsight.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for 한국은행 ECOS (Economic Statistics System) open API.
 *
 * Endpoint shape, verified against the real API with a live key:
 * {base}/StatisticSearch/{apiKey}/json/kr/1/100/{statCode}/{cycle}/{start}/{end}/{itemCode1}
 * — 722Y001 (기준금리) is monthly ("M", yyyyMM), 731Y001 (원/달러 환율) is daily
 * ("D", yyyyMMdd) only; requesting it with "M" silently returns zero rows.
 *
 * Same defensive pattern as the other external clients: any failure (missing
 * key, network error, unexpected shape) falls back to a plausible flat
 * sample value with a warn log, never an exception bubbling up.
 */
@Component
public class EcosClient {

    private static final Logger log = LoggerFactory.getLogger(EcosClient.class);

    private static final String BASE_RATE_STAT_CODE = "722Y001"; // 한국은행 기준금리
    private static final String BASE_RATE_ITEM_CODE = "0101000";

    private static final String USD_KRW_STAT_CODE = "731Y001"; // 원/달러 환율(매매기준율)
    private static final String USD_KRW_ITEM_CODE = "0000001";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WebClient webClient;
    private final String apiKey;

    public EcosClient(WebClient.Builder webClientBuilder,
                       @Value("${finsight.ecos.base-url}") String baseUrl,
                       @Value("${finsight.ecos.api-key}") String apiKey) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public EcosRate getBaseRate() {
        // 722Y001 (기준금리) is only published monthly. A policy rate is
        // conventionally read in percentage POINTS (e.g. "+0.25%p"), not a
        // relative % change — 2.75 -> 3.00 is only +0.25%p but would read as
        // a wildly misleading "+9.1%" if treated as a relative change like FX.
        return fetchLatestValue("기준금리", BASE_RATE_STAT_CODE, BASE_RATE_ITEM_CODE, "M",
                LocalDate.now().minusMonths(3).format(YYYYMM), LocalDate.now().format(YYYYMM), 3.50, true);
    }

    public EcosRate getUsdKrwRate() {
        // 731Y001 (원/달러 매매기준율) is only published daily — verified against the
        // real ECOS API: requesting it with cycle "M" / yyyyMM dates returns no rows.
        return fetchLatestValue("원/달러 환율", USD_KRW_STAT_CODE, USD_KRW_ITEM_CODE, "D",
                LocalDate.now().minusDays(14).format(YYYYMMDD), LocalDate.now().format(YYYYMMDD), 1380.0, false);
    }

    private EcosRate fetchLatestValue(String seriesName, String statCode, String itemCode, String cycle,
                                       String start, String end, double fallbackValue, boolean pointDiff) {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("ECOS API key not configured, using fallback value for {}", seriesName);
            return fallback(seriesName, fallbackValue);
        }
        try {
            // Request a wide-enough row range (not just 1/1) — ECOS returns rows in
            // ascending chronological order, so asking for only the 1st row in a
            // multi-row window silently returns the OLDEST value, not the latest.
            // Verified against the real API with a live key.
            String path = String.format(
                    "/StatisticSearch/%s/json/kr/1/100/%s/%s/%s/%s/%s",
                    apiKey, statCode, cycle, start, end, itemCode
            );
            JsonNode response = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            if (response == null) {
                return fallback(seriesName, fallbackValue);
            }
            JsonNode rows = response.path("StatisticSearch").path("row");
            if (!rows.isArray() || rows.isEmpty()) {
                log.warn("ECOS response had no rows for {}, using fallback", seriesName);
                return fallback(seriesName, fallbackValue);
            }
            JsonNode latest = rows.get(rows.size() - 1);
            double value = Double.parseDouble(latest.path("DATA_VALUE").asText(String.valueOf(fallbackValue)).trim());
            String period = latest.path("TIME").asText(end);
            Double changePercent = computeChange(rows, value, pointDiff);
            return new EcosRate(seriesName, value, period, changePercent, false);
        } catch (Exception e) {
            log.warn("ECOS API call failed for {}, using fallback: {}", seriesName, e.getMessage());
            return fallback(seriesName, fallbackValue);
        }
    }

    // Change vs. the row immediately before the latest one — was previously
    // missing entirely, so the frontend always showed a hardcoded sample
    // change value (e.g. "-0.18%") next to the real fetched rate/price.
    // pointDiff=true returns a raw point difference (base rate); false
    // returns a relative % change (FX rate).
    private Double computeChange(JsonNode rows, double latestValue, boolean pointDiff) {
        if (rows.size() < 2) {
            return null;
        }
        try {
            double previousValue = Double.parseDouble(rows.get(rows.size() - 2).path("DATA_VALUE").asText().trim());
            if (pointDiff) {
                return Math.round((latestValue - previousValue) * 100.0) / 100.0;
            }
            if (previousValue == 0.0) {
                return null;
            }
            return Math.round((latestValue - previousValue) / previousValue * 1000.0) / 10.0;
        } catch (Exception e) {
            return null;
        }
    }

    private EcosRate fallback(String seriesName, double value) {
        return new EcosRate(seriesName, value, "N/A", null, true);
    }
}
