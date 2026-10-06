package com.finsight.external.kis;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

class KisRequestGuardTest {
    @Test void quotesAreSpacedAndHttpFailureStopsFurtherRequests() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var provider = new KisTokenProvider(WebClient.builder(), mock(StringRedisTemplate.class), server.url("/").toString(), "", "");
            var client = WebClient.builder().baseUrl(server.url("/").toString()).filter(provider.quoteFilter()).build();
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(503));
            client.get().retrieve().toBodilessEntity().block();
            long before = System.nanoTime();
            assertThrows(RuntimeException.class, () -> client.get().retrieve().toBodilessEntity().block());
            assertTrue((System.nanoTime()-before)/1_000_000 > 900);
            assertThrows(RuntimeException.class, () -> client.get().retrieve().toBodilessEntity().block());
            assertEquals(2, server.getRequestCount());
        }
    }
}
