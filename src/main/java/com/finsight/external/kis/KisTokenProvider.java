package com.finsight.external.kis;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Issues and Redis-caches the KIS OAuth access token. This is the only
 * class responsible for KIS authentication; the quote/candle clients depend
 * on it rather than issuing tokens themselves. KIS rate-limits token
 * issuance, so a successful token is cached for ~23h.
 */
@Component
public class KisTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(KisTokenProvider.class);

    private static final String TOKEN_PATH = "/oauth2/tokenP";
    private static final String REDIS_TOKEN_KEY = "finsight:kis:access-token";
    private static final Duration TOKEN_TTL = Duration.ofHours(23);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final StringRedisTemplate redisTemplate;
    private final String appKey;
    private final String appSecret;

    public KisTokenProvider(WebClient.Builder webClientBuilder,
                             StringRedisTemplate redisTemplate,
                             @Value("${finsight.kis.base-url}") String baseUrl,
                             @Value("${finsight.kis.app-key}") String appKey,
                             @Value("${finsight.kis.app-secret}") String appSecret) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.redisTemplate = redisTemplate;
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public Optional<String> getAccessToken() {
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            log.warn("KIS app-key/app-secret not configured, skipping token issuance");
            return Optional.empty();
        }
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_TOKEN_KEY);
            if (StringUtils.hasText(cached)) {
                return Optional.of(cached);
            }
            return issueAndCacheToken();
        } catch (Exception e) {
            log.warn("KIS access token issuance failed, using fallback: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> issueAndCacheToken() {
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "appsecret", appSecret
        );
        JsonNode response = webClient.post()
                .uri(TOKEN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(CALL_TIMEOUT)
                .block();
        if (response == null || !response.hasNonNull("access_token")) {
            log.warn("KIS token issuance returned no access_token");
            return Optional.empty();
        }
        String token = response.get("access_token").asText();
        redisTemplate.opsForValue().set(REDIS_TOKEN_KEY, token, TOKEN_TTL);
        return Optional.of(token);
    }
}
