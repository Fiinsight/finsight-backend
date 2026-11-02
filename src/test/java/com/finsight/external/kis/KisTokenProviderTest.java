package com.finsight.external.kis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;

class KisTokenProviderTest {
    @Test void slowInitialTokenIsCachedWithoutIssuingAnotherToken() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"access_token\":\"fixture-token\"}").setBodyDelay(6, TimeUnit.SECONDS));
            var redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get("finsight:kis:access-token")).thenReturn(null, null, "fixture-token");
            var provider = new KisTokenProvider(WebClient.builder(), redis, server.url("/").toString(), "fixture-key", "fixture-secret");
            assertEquals("fixture-token", provider.getAccessToken().orElseThrow());
            assertEquals("fixture-token", provider.getAccessToken().orElseThrow());
            verify(values).set("finsight:kis:access-token", "fixture-token", Duration.ofHours(23));
            assertEquals(1, server.getRequestCount());
        }
    }
}
