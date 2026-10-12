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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Fetches recent domestic-stock intraday OHLC bars from KIS. */
@Component
public class KisMinuteCandleClient {

    private static final Logger log = LoggerFactory.getLogger(KisMinuteCandleClient.class);
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String TR_ID = "FHKST03010200";
    private static final int MAX_PAGES = 14;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final WebClient webClient;
    private final KisTokenProvider tokenProvider;
    private final String appKey;
    private final String appSecret;
    private final Cache<String, KisMinuteCandleResult> cache = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(CACHE_TTL).build();

    public KisMinuteCandleClient(WebClient.Builder webClientBuilder,
                                 KisTokenProvider tokenProvider,
                                 @Value("${finsight.kis.base-url}") String baseUrl,
                                 @Value("${finsight.kis.app-key}") String appKey,
                                 @Value("${finsight.kis.app-secret}") String appSecret) {
        this.webClient = webClientBuilder.clone().baseUrl(baseUrl).filter(tokenProvider.quoteFilter()).build();
        this.tokenProvider = tokenProvider;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    /** @param intervalMinutes one of 1, 5, or 15 */
    public List<KisMinuteCandle> getCandles(String stockCode, int intervalMinutes, int count) {
        return getCandlesWithStatus(stockCode, intervalMinutes, count).candles();
    }

    public KisMinuteCandleResult getCandlesWithStatus(String stockCode, int intervalMinutes, int count) {
        int interval = normalizeInterval(intervalMinutes);
        int safeCount = Math.max(1, Math.min(count, 120));
        return cache.get(stockCode + ":" + interval + ":" + safeCount,
                key -> fetchCandles(stockCode, interval, safeCount));
    }

    private KisMinuteCandleResult fetchCandles(String stockCode, int interval, int safeCount) {
        Map<LocalDateTime, KisMinuteCandle> collected = new java.util.TreeMap<>();
        try {
            Optional<String> token = tokenProvider.getAccessToken();
            if (token.isEmpty()) {
                return new KisMinuteCandleResult(fallback(interval, safeCount), true);
            }
            String cursor = requestTime();
            LocalDate tradingDate = null;
            for (int page = 0; page < MAX_PAGES; page++) {
            final String requestTime = cursor;
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
                    break;
                }
                List<KisMinuteCandle> rows = parseCandles(response, 1, Integer.MAX_VALUE);
                if (rows.isEmpty()) break;
                if (tradingDate == null) tradingDate = rows.getLast().timestamp().toLocalDate();
                final LocalDate session = tradingDate;
                var sessionRows = rows.stream().filter(row -> row.timestamp().toLocalDate().equals(session)
                        && !row.timestamp().toLocalTime().isBefore(MARKET_OPEN)).toList();
                if (sessionRows.isEmpty()) break;
                int before = collected.size();
                sessionRows.forEach(row -> collected.putIfAbsent(row.timestamp(), row));
                LocalTime earliest = sessionRows.getFirst().timestamp().toLocalTime();
                // Continue through the oldest bucket so its OHLC is not truncated at a page boundary.
                if (collected.size() == before || !earliest.isAfter(MARKET_OPEN)
                        || collected.size() >= safeCount * interval + interval) break;
                String nextCursor = earliest.minusSeconds(1).format(TIME);
                if (nextCursor.compareTo(cursor) >= 0) break;
                cursor = nextCursor;
            }
        } catch (WebClientResponseException e) {
            if (e.getResponseBodyAsString().contains("EGW00201")) {
                log.warn("KIS minute candle rate limit reached for {} ({}m), returning collected candles", stockCode, interval);
            } else {
                log.warn("KIS minute candle HTTP {} for {}, returning collected candles: {}", e.getStatusCode().value(), stockCode, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("KIS minute candle call failed for {} ({}m), returning collected candles: {}", stockCode, interval, e.getMessage());
        }
        List<KisMinuteCandle> rows = new ArrayList<>(collected.values());
        Map<LocalDateTime, List<KisMinuteCandle>> buckets = new java.util.TreeMap<>();
        for (var row : rows) {
            var time = row.timestamp();
            var bucket = time.withMinute(time.getMinute() / interval * interval).withSecond(0).withNano(0);
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(row);
        }
        var result = buckets.entrySet().stream().map(entry -> aggregate(entry.getKey(), entry.getValue())).toList();
        return new KisMinuteCandleResult(result.subList(Math.max(0, result.size() - safeCount), result.size()), result.isEmpty());
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

    private List<KisMinuteCandle> fallback(int interval, int count) { return List.of(); }

    private int normalizeInterval(int interval) {
        return interval == 1 || interval == 5 || interval == 15 ? interval : 5;
    }

}
