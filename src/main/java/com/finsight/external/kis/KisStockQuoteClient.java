package com.finsight.external.kis;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fetches current price/change for a single stock symbol from KIS.
 *
 * TODO: KIS 공식 문서(https://apiportal.koreainvestment.com) 대조 필요 — tr_id/endpoint는 best-effort로 작성함
 */
@Component
public class KisStockQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(KisStockQuoteClient.class);

    private static final String STOCK_QUOTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String STOCK_QUOTE_TR_ID = "FHKST01010100";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final KisTokenProvider tokenProvider;
    private final String appKey;
    private final String appSecret;

    public KisStockQuoteClient(WebClient.Builder webClientBuilder,
                                KisTokenProvider tokenProvider,
                                @Value("${finsight.kis.base-url}") String baseUrl,
                                @Value("${finsight.kis.app-key}") String appKey,
                                @Value("${finsight.kis.app-secret}") String appSecret) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public KisStockQuote getStockQuote(String stockCode) {
        try {
            Optional<String> token = tokenProvider.getAccessToken();
            if (token.isEmpty()) {
                return fallback(stockCode);
            }
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(STOCK_QUOTE_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .build())
                    .headers(headers -> KisApiHeaders.apply(headers, token.get(), STOCK_QUOTE_TR_ID, appKey, appSecret))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            JsonNode output = response == null ? null : response.get("output");
            if (output == null) {
                return fallback(stockCode);
            }
            double currentPrice = KisJsonNumbers.parseDouble(output, "stck_prpr");
            double changePercent = KisJsonNumbers.parseDouble(output, "prdy_ctrt");
            return new KisStockQuote(stockCode, currentPrice, changePercent, false);
        } catch (Exception e) {
            log.warn("KIS stock quote call failed for {}, using fallback: {}", stockCode, e.getMessage());
            return fallback(stockCode);
        }
    }

    private KisStockQuote fallback(String stockCode) {
        return new KisStockQuote(stockCode, 70000.0, 0.0, true);
    }
}
