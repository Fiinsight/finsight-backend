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
 * Fetches current value/change for a market index (코스피/코스닥) from KIS.
 *
 * TODO: KIS 공식 문서(https://apiportal.koreainvestment.com) 대조 필요 — tr_id/endpoint는 best-effort로 작성함
 */
@Component
public class KisIndexQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(KisIndexQuoteClient.class);

    private static final String INDEX_QUOTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-index-price";
    private static final String INDEX_QUOTE_TR_ID = "FHPUP02100000";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final KisTokenProvider tokenProvider;
    private final String appKey;
    private final String appSecret;

    public KisIndexQuoteClient(WebClient.Builder webClientBuilder,
                                KisTokenProvider tokenProvider,
                                @Value("${finsight.kis.base-url}") String baseUrl,
                                @Value("${finsight.kis.app-key}") String appKey,
                                @Value("${finsight.kis.app-secret}") String appSecret) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public KisIndexQuote getIndexQuote(String indexCode) {
        try {
            Optional<String> token = tokenProvider.getAccessToken();
            if (token.isEmpty()) {
                return fallback(indexCode);
            }
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(INDEX_QUOTE_PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                            .queryParam("FID_INPUT_ISCD", indexCode)
                            .build())
                    .headers(headers -> KisApiHeaders.apply(headers, token.get(), INDEX_QUOTE_TR_ID, appKey, appSecret))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            JsonNode output = response == null ? null : response.get("output");
            if (output == null) {
                return fallback(indexCode);
            }
            double currentValue = KisJsonNumbers.parseDouble(output, "bstp_nmix_prpr");
            double changePercent = KisJsonNumbers.parseDouble(output, "bstp_nmix_prdy_ctrt");
            return new KisIndexQuote(indexCode, currentValue, changePercent, false);
        } catch (Exception e) {
            log.warn("KIS index quote call failed for {}, using fallback: {}", indexCode, e.getMessage());
            return fallback(indexCode);
        }
    }

    private KisIndexQuote fallback(String indexCode) {
        // Plausible flat sample values so the home screen never breaks.
        double sample = "1001".equals(indexCode) ? 780.0 : 2650.0;
        return new KisIndexQuote(indexCode, sample, 0.0, true);
    }
}
