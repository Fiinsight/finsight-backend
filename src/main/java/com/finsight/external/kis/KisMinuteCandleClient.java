package com.finsight.external.kis;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Fetches recent domestic-stock intraday OHLC bars from KIS. */
@Component
public class KisMinuteCandleClient {

    private static final Logger log = LoggerFactory.getLogger(KisMinuteCandleClient.class);
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String TR_ID = "FHKST03010200";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final WebClient webClient;
    private final KisTokenProvider tokenProvider;
    private final String appKey;
    private final String appSecret;

    public KisMinuteCandleClient(WebClient.Builder webClientBuilder,
                                 KisTokenProvider tokenProvider,
                                 @Value("${finsight.kis.base-url}") String baseUrl,
                                 @Value("${finsight.kis.app-key}") String appKey,
                                 @Value("${finsight.kis.app-secret}") String appSecret) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    /** @param intervalMinutes one of 1, 5, or 15 */
    public List<KisMinuteCandle> getCandles(String stockCode, int intervalMinutes, int count) {
        return getCandlesWithStatus(stockCode, intervalMinutes, count).candles();
    }

    @Cacheable(cacheNames = "kisMinuteCandles", key = "#stockCode + ':' + #intervalMinutes + ':' + #count",
            unless = "#result == null || #result.fallback()")
    public KisMinuteCandleResult getCandlesWithStatus(String stockCode, int intervalMinutes, int count) {
        int interval = normalizeInterval(intervalMinutes);
        int safeCount = Math.max(1, Math.min(count, 120));
        try {
            Optional<String> token = tokenProvider.getAccessToken();
            if (token.isEmpty()) {
                return new KisMinuteCandleResult(fallback(interval, safeCount), true);
            }
            String requestTime = requestTime();
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(PATH)
                            .queryParam("FID_ETC_CLS_CODE", "")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_HOUR_1", requestTime)
                            .queryParam("FID_PW_DATA_INCU_YN", "Y")
                            .build())
                    .headers(headers -> KisApiHeaders.apply(headers, token.get(), TR_ID, appKey, appSecret))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            if (response == null || !"0".equals(response.path("rt_cd").asText())) {
                log.warn("KIS minute candle API rejected request for {} ({}m): {} {}",
                        stockCode,
                        interval,
                        response == null ? "no response" : response.path("msg_cd").asText(""),
                        response == null ? "" : response.path("msg1").asText(""));
                return new KisMinuteCandleResult(fallback(interval, safeCount), true);
            }
            List<KisMinuteCandle> candles = parseCandles(response, interval, safeCount);
            return candles.isEmpty()
                    ? new KisMinuteCandleResult(fallback(interval, safeCount), true)
                    : new KisMinuteCandleResult(candles, false);
        } catch (Exception e) {
            log.warn("KIS minute candle call failed for {} ({}m), using fallback: {}", stockCode, interval, e.getMessage());
            return new KisMinuteCandleResult(fallback(interval, safeCount), true);
        }
    }

    private String requestTime() {
        LocalTime now = LocalTime.now(KST);
        // KIS requires a valid HHmmss cursor even outside regular trading hours.
        // 15:30 is also the useful cursor for its previous-session data mode.
        LocalTime cursor = now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE) ? MARKET_CLOSE : now;
        return cursor.format(TIME);
    }

    private List<KisMinuteCandle> parseCandles(JsonNode response, int interval, int count) {
        JsonNode output2 = response == null ? null : response.get("output2");
        List<KisMinuteCandle> candles = new ArrayList<>();
        if (output2 == null || !output2.isArray()) {
            return candles;
        }
        for (JsonNode row : output2) {
            String dateText = row.path("stck_bsop_date").asText("");
            String timeText = row.path("stck_cntg_hour").asText("");
            if (dateText.length() != 8 || timeText.length() < 4) {
                continue;
            }
            try {
                String normalizedTime = timeText.length() == 4 ? timeText + "00" : timeText.substring(0, 6);
                candles.add(new KisMinuteCandle(
                        LocalDateTime.of(LocalDate.parse(dateText, DATE), LocalTime.parse(normalizedTime, TIME)),
                        KisJsonNumbers.parseDouble(row, "stck_oprc"),
                        KisJsonNumbers.parseDouble(row, "stck_hgpr"),
                        KisJsonNumbers.parseDouble(row, "stck_lwpr"),
                        KisJsonNumbers.parseDouble(row, "stck_prpr")
                ));
            } catch (RuntimeException ignored) {
                // Ignore malformed rows and preserve the remaining valid bars.
            }
        }
        List<KisMinuteCandle> sorted = candles.stream()
                .sorted(Comparator.comparing(KisMinuteCandle::timestamp))
                .toList();
        if (interval == 1) {
            return sorted.stream().limit(count).toList();
        }
        Map<LocalDateTime, List<KisMinuteCandle>> buckets = new LinkedHashMap<>();
        for (KisMinuteCandle candle : sorted) {
            LocalDateTime time = candle.timestamp();
            int bucketMinute = (time.getMinute() / interval) * interval;
            LocalDateTime bucket = time.withMinute(bucketMinute).withSecond(0).withNano(0);
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(candle);
        }
        return buckets.entrySet().stream()
                .map(entry -> aggregate(entry.getKey(), entry.getValue()))
                .limit(count)
                .toList();
    }

    private KisMinuteCandle aggregate(LocalDateTime bucket, List<KisMinuteCandle> source) {
        KisMinuteCandle first = source.get(0);
        KisMinuteCandle last = source.get(source.size() - 1);
        double high = source.stream().mapToDouble(KisMinuteCandle::high).max().orElse(first.high());
        double low = source.stream().mapToDouble(KisMinuteCandle::low).min().orElse(first.low());
        return new KisMinuteCandle(bucket, first.open(), high, low, last.close());
    }

    private List<KisMinuteCandle> fallback(int interval, int count) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        double base = 70_000.0;
        List<KisMinuteCandle> candles = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            LocalDateTime timestamp = now.minusMinutes((long) i * interval);
            double close = base + ((count - i) % 7 - 3) * 25.0;
            candles.add(new KisMinuteCandle(timestamp, close, close + 40, close - 40, close));
        }
        return candles;
    }

    private int normalizeInterval(int interval) {
        return interval == 1 || interval == 5 || interval == 15 ? interval : 5;
    }
}
