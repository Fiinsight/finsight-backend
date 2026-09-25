package com.finsight.external;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.finsight.config.ExternalCacheConfiguration;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

class ExternalClientCacheTest {

    @Test
    void cachesSuccessfulNaverResponseWithinTtl() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(json("""
                    {"exchangeInfo":{"closePrice":"1,350.00","fluctuationsRatio":"0.1","localTradedAt":"2026-09-25T15:00:00+09:00"}}
                    """));
            try (var context = context(server)) {
                NaverFxClient client = context.getBean(NaverFxClient.class);

                assertEquals(1350.0, client.getUsdKrwRate().value());
                assertEquals(1350.0, client.getUsdKrwRate().value());
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    void doesNotCacheFallbackResponses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(json("{}"));
            server.enqueue(json("{}"));
            try (var context = context(server)) {
                NaverFxClient client = context.getBean(NaverFxClient.class);

                assertEquals(true, client.getUsdKrwRate().fallback());
                assertEquals(true, client.getUsdKrwRate().fallback());
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    private AnnotationConfigApplicationContext context(MockWebServer server) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "finsight.naver-fx.base-url", server.url("/").toString())));
        context.registerBean(WebClient.Builder.class, WebClient::builder);
        context.register(ExternalCacheConfiguration.class, NaverFxClient.class);
        context.refresh();
        return context;
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
