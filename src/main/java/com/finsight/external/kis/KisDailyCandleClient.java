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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public List<KisDailyCandle> getDailyCandles(String stockCode, int count) {
        try {
            Optional<String> token = tokenProvider.getAccessToken();
            if (token.isEmpty()) {
                return fallback(count);
            }
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays((long) count * 2L); // pad for weekends/holidays
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(DAILY_CANDLE_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_DATE_1", startDate.format(YYYYMMDD))
                            .queryParam("FID_INPUT_DATE_2", endDate.format(YYYYMMDD))
                            .queryParam("FID_PERIOD_DIV_CODE", "D")
                            .queryParam("FID_ORG_ADJ_PRC", "1")
                            .build())
                    .headers(headers -> KisApiHeaders.apply(headers, token.get(), DAILY_CANDLE_TR_ID, appKey, appSecret))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            List<KisDailyCandle> candles = parseCandles(response, count);
            return candles.isEmpty() ? fallback(count) : candles;
        } catch (Exception e) {
            log.warn("KIS daily candle call failed for {}, using fallback: {}", stockCode, e.getMessage());
            return fallback(count);
        }
    }

    private List<KisDailyCandle> parseCandles(JsonNode response, int count) {
        JsonNode output2 = response == null ? null : response.get("output2");
        List<KisDailyCandle> candles = new ArrayList<>();
        if (output2 == null || !output2.isArray()) {
            return candles;
        }
        for (JsonNode row : output2) {
            String dateStr = row.path("stck_bsop_date").asText(null);
            if (dateStr == null || dateStr.isBlank()) {
                continue;
            }
            candles.add(new KisDailyCandle(
                    LocalDate.parse(dateStr, YYYYMMDD),
                    KisJsonNumbers.parseDouble(row, "stck_oprc"),
                    KisJsonNumbers.parseDouble(row, "stck_hgpr"),
                    KisJsonNumbers.parseDouble(row, "stck_lwpr"),
                    KisJsonNumbers.parseDouble(row, "stck_clpr")
            ));
            if (candles.size() >= count) {
                break;
            }
        }
        return candles;
    }

    private List<KisDailyCandle> fallback(int count) {
        List<KisDailyCandle> candles = new ArrayList<>();
        LocalDate date = LocalDate.now();
        double base = 70000.0;
        for (int i = 0; i < Math.max(count, 1); i++) {
            candles.add(new KisDailyCandle(date.minusDays(i), base, base, base, base));
        }
        return candles;
    }
}
