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
 * Endpoint shape is best-effort from the public ECOS documentation:
 * {base}/StatisticSearch/{apiKey}/json/kr/1/1/{statCode}/{cycle}/{start}/{end}/{itemCode1}
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

    private final WebClient webClient;
    private final String apiKey;

    public EcosClient(WebClient.Builder webClientBuilder,
                       @Value("${finsight.ecos.base-url}") String baseUrl,
                       @Value("${finsight.ecos.api-key}") String apiKey) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public EcosRate getBaseRate() {
        return fetchLatestValue("기준금리", BASE_RATE_STAT_CODE, BASE_RATE_ITEM_CODE, 3.50);
    }

    public EcosRate getUsdKrwRate() {
        return fetchLatestValue("원/달러 환율", USD_KRW_STAT_CODE, USD_KRW_ITEM_CODE, 1380.0);
    }

    private EcosRate fetchLatestValue(String seriesName, String statCode, String itemCode, double fallbackValue) {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("ECOS API key not configured, using fallback value for {}", seriesName);
            return fallback(seriesName, fallbackValue);
        }
        try {
            LocalDate now = LocalDate.now();
            String end = now.format(YYYYMM);
            String start = now.minusMonths(3).format(YYYYMM);
            String path = String.format(
                    "/StatisticSearch/%s/json/kr/1/1/%s/M/%s/%s/%s",
                    apiKey, statCode, start, end, itemCode
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
            return new EcosRate(seriesName, value, period, false);
        } catch (Exception e) {
            log.warn("ECOS API call failed for {}, using fallback: {}", seriesName, e.getMessage());
            return fallback(seriesName, fallbackValue);
        }
    }

    private EcosRate fallback(String seriesName, double value) {
        return new EcosRate(seriesName, value, "N/A", true);
    }
}
