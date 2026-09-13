package com.finsight.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for Naver Finance's public market-index endpoint, used here only for
 * USD/KRW.
 *
 * ECOS's 731Y001 (원/달러 매매기준율) is published once each morning by 서울외국환중개
 * and stays fixed the rest of the day — not what a "실시간 환율" feature needs.
 * This endpoint instead mirrors a commercial bank's 고시환율 (here: 하나은행),
 * which is republished many times per trading day (see `degreeCount`, the
 * 고시회차 counter) — the same figure shown on Naver/bank apps. No API key,
 * verified live: https://api.stock.naver.com/marketindex/exchange/FX_USDKRW
 */
@Component
public class NaverFxClient {

    private static final Logger log = LoggerFactory.getLogger(NaverFxClient.class);
    private static final String SERIES_NAME = "원/달러 환율";
    private static final double FALLBACK_VALUE = 1380.0;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final WebClient webClient;

    public NaverFxClient(WebClient.Builder webClientBuilder,
                          @Value("${finsight.naver-fx.base-url}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public EcosRate getUsdKrwRate() {
        try {
            JsonNode response = webClient.get()
                    .uri("/marketindex/exchange/FX_USDKRW")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            if (response == null) {
                return fallback();
            }
            JsonNode info = response.path("exchangeInfo");
            if (info.isMissingNode()) {
                log.warn("Naver FX response had no exchangeInfo, using fallback");
                return fallback();
            }
            double value = Double.parseDouble(info.path("closePrice").asText("").replace(",", ""));
            Double changePercent = parseChangePercent(info);
            String asOfPeriod = parseAsOfPeriod(info);
            return new EcosRate(SERIES_NAME, value, asOfPeriod, changePercent, false);
        } catch (Exception e) {
            log.warn("Naver FX API call failed, using fallback: {}", e.getMessage());
            return fallback();
        }
    }

    private Double parseChangePercent(JsonNode info) {
        try {
            return Double.parseDouble(info.path("fluctuationsRatio").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private String parseAsOfPeriod(JsonNode info) {
        try {
            OffsetDateTime tradedAt = OffsetDateTime.parse(info.path("localTradedAt").asText());
            return TIME_FORMAT.format(tradedAt) + " 고시";
        } catch (Exception e) {
            return info.path("localTradedAt").asText("N/A");
        }
    }

    private EcosRate fallback() {
        return new EcosRate(SERIES_NAME, FALLBACK_VALUE, "N/A", null, true);
    }
}
