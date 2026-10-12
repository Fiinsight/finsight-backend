package com.finsight.external.kis;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Fetches recent daily OHLC candles for a stock symbol from KIS.
 *
 * TODO: KIS 공식 문서(https://apiportal.koreainvestment.com) 대조 필요 — tr_id/endpoint는 best-effort로 작성함
 */
@Component
public class KisDailyCandleClient {

    private static final Logger log = LoggerFactory.getLogger(KisDailyCandleClient.class);

    private static final String DAILY_CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String DAILY_CANDLE_TR_ID = "FHKST03010100";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WebClient webClient;
    private final KisTokenProvider tokenProvider;
    private final String appKey;
    private final String appSecret;

    public KisDailyCandleClient(WebClient.Builder webClientBuilder,
                                 KisTokenProvider tokenProvider,
                                 @Value("${finsight.kis.base-url}") String baseUrl,
                                 @Value("${finsight.kis.app-key}") String appKey,
                                 @Value("${finsight.kis.app-secret}") String appSecret) {
        this.webClient = webClientBuilder.clone().baseUrl(baseUrl).filter(tokenProvider.quoteFilter()).build();
        this.tokenProvider = tokenProvider;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public List<KisDailyCandle> getDailyCandles(String stockCode, int count) {
        return getCandles(stockCode, count, "D");
    }

    /**
     * @param periodDivCode KIS FID_PERIOD_DIV_CODE: "D"(일봉)/"W"(주봉)/"M"(월봉)
     */
    public List<KisDailyCandle> getCandles(String stockCode, int count, String periodDivCode) {
        return getCandlesWithStatus(stockCode, count, periodDivCode).candles();
    }

    @Cacheable(cacheNames = "kisDailyCandles", key = "#stockCode + ':' + #count + ':' + #periodDivCode", sync = true)
    public KisDailyCandleResult getCandlesWithStatus(String stockCode, int count, String periodDivCode) {
        LocalDate end = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        long days = switch (periodDivCode) { case "W" -> count * 10L; case "M" -> count * 35L; default -> count * 2L; };
        return fetch(stockCode, count, periodDivCode, end.minusDays(days), end);
    }

    @Cacheable(cacheNames = "kisDailyCandles", key = "'close:' + #symbol + ':' + #start + ':' + #end", sync = true)
    public KisDailyCandleResult getClosingCandles(String symbol, LocalDate start, LocalDate end) {
        return fetch(symbol, 100, "D", start, end);
    }

    private KisDailyCandleResult fetch(String stockCode, int count, String periodDivCode, LocalDate startDate, LocalDate endDate) {
        boolean index = "KOSPI".equals(stockCode) || "KOSDAQ".equals(stockCode);
        if (!index && !stockCode.matches("\\d{6}")) return new KisDailyCandleResult(List.of(), true);
        String code = index ? ("KOSPI".equals(stockCode) ? "0001" : "1001") : stockCode;
        try {
            Optional<String> token = tokenProvider.getAccessToken();
            if (token.isEmpty()) return new KisDailyCandleResult(List.of(), true);
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(index ? "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice" : DAILY_CANDLE_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", index ? "U" : "J")
                            .queryParam("FID_INPUT_ISCD", code)
                            .queryParam("FID_INPUT_DATE_1", startDate.format(YYYYMMDD))
                            .queryParam("FID_INPUT_DATE_2", endDate.format(YYYYMMDD))
                            .queryParam("FID_PERIOD_DIV_CODE", periodDivCode)
                            .queryParam("FID_ORG_ADJ_PRC", "1")
                            .build())
                    .headers(headers -> KisApiHeaders.apply(headers, token.get(), index ? "FHKUP03500100" : DAILY_CANDLE_TR_ID, appKey, appSecret))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            List<KisDailyCandle> candles = parseCandles(response, count, index);
            return candles.isEmpty()
                    ? new KisDailyCandleResult(fallback(count), true)
                    : new KisDailyCandleResult(candles, false);
        } catch (WebClientResponseException e) {
            if (e.getResponseBodyAsString().contains("EGW00201")) {
                log.warn("KIS daily candle rate limit reached for {} ({}), using fallback", stockCode, periodDivCode);
            } else {
                log.warn("KIS daily candle HTTP {} for {}, using fallback: {}", e.getStatusCode().value(), stockCode, e.getMessage());
            }
            return new KisDailyCandleResult(fallback(count), true);
        } catch (Exception e) {
            log.warn("KIS daily candle call failed for {}, using fallback: {}", stockCode, e.getMessage());
            return new KisDailyCandleResult(fallback(count), true);
        }
    }

    private List<KisDailyCandle> parseCandles(JsonNode response, int count, boolean index) {
        JsonNode output2 = response == null ? null : response.get("output2");
        List<KisDailyCandle> candles = new ArrayList<>();
        if (response == null || !"0".equals(response.path("rt_cd").asText("0")) || output2 == null || !output2.isArray()) {
            return candles;
        }
        for (JsonNode row : output2) {
            String dateStr = row.path("stck_bsop_date").asText(null);
            if (dateStr == null || dateStr.isBlank()) {
                continue;
            }
            candles.add(new KisDailyCandle(
                    LocalDate.parse(dateStr, YYYYMMDD),
                    KisJsonNumbers.requiredDouble(row, index ? "bstp_nmix_oprc" : "stck_oprc"),
                    KisJsonNumbers.requiredDouble(row, index ? "bstp_nmix_hgpr" : "stck_hgpr"),
                    KisJsonNumbers.requiredDouble(row, index ? "bstp_nmix_lwpr" : "stck_lwpr"),
                    KisJsonNumbers.requiredDouble(row, index ? "bstp_nmix_prpr" : "stck_clpr")
            ));
            if (candles.size() >= count) {
                break;
            }
        }
        return candles;
    }

    private List<KisDailyCandle> fallback(int count) {
        return List.of();
    }
}
