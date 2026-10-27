package com.finsight.external.kis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finsight.config.ExternalCacheConfiguration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

class KisFailureRecoveryTest {
    private AnnotationConfigApplicationContext context(MockWebServer server, Class<?> client) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "finsight.kis.base-url", server.url("/").toString(),
                "finsight.kis.app-key", "fixture-key", "finsight.kis.app-secret", "fixture-secret")));
        var token = mock(KisTokenProvider.class);
        when(token.getAccessToken()).thenReturn(Optional.of("fixture-token"));
        when(token.quoteFilter()).thenReturn((request, next) -> next.exchange(request));
        context.registerBean(KisTokenProvider.class, () -> token);
        context.registerBean(WebClient.Builder.class, WebClient::builder);
        context.register(ExternalCacheConfiguration.class, client);
        context.refresh();
        return context;
    }
    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
    @Test void indexRecoversBeforeTtlAndCachesOnlyTheRecoveredQuote() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("{}"));
            server.enqueue(json("{\"output\":{\"bstp_nmix_prpr\":\"2500\",\"bstp_nmix_prdy_ctrt\":\"1.0\"}}"));
            try (var context = context(server, KisIndexQuoteClient.class)) {
                var client = context.getBean(KisIndexQuoteClient.class);
                assertTrue(client.getIndexQuote("0001").fallback());
                assertFalse(client.getIndexQuote("0001").fallback());
                assertEquals(2500, client.getIndexQuote("0001").currentValue());
                assertEquals(2, server.getRequestCount());
            }
        }
    }
    @Test void stockRecoversBeforeTtlAndCachesOnlyTheRecoveredQuote() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("{}"));
            server.enqueue(json("{\"output\":{\"stck_prpr\":\"70000\",\"prdy_ctrt\":\"1.0\"}}"));
            try (var context = context(server, KisStockQuoteClient.class)) {
                var client = context.getBean(KisStockQuoteClient.class);
                assertTrue(client.getStockQuote("005930").fallback());
                assertFalse(client.getStockQuote("005930").fallback());
                assertEquals(70000, client.getStockQuote("005930").currentPrice());
                assertEquals(2, server.getRequestCount());
            }
        }
    }
    @Test void dailyAndClosingCandlesRecoverBeforeFiveMinuteTtl() throws Exception {
        try (var server = new MockWebServer()) {
            var valid = "{\"output2\":[{\"stck_bsop_date\":\"20261026\",\"stck_oprc\":\"100\",\"stck_hgpr\":\"110\",\"stck_lwpr\":\"90\",\"stck_clpr\":\"105\"}]}";
            server.enqueue(json("{}")); server.enqueue(json(valid));
            server.enqueue(json("{}")); server.enqueue(json(valid));
            try (var context = context(server, KisDailyCandleClient.class)) {
                var client = context.getBean(KisDailyCandleClient.class);
                assertTrue(client.getCandlesWithStatus("005930", 30, "D").fallback());
                assertFalse(client.getCandlesWithStatus("005930", 30, "D").fallback());
                assertEquals(1, client.getCandlesWithStatus("005930", 30, "D").candles().size());
                var day = LocalDate.of(2026, 10, 26);
                assertTrue(client.getClosingCandles("005930", day, day).fallback());
                assertFalse(client.getClosingCandles("005930", day, day).fallback());
                assertEquals(1, client.getClosingCandles("005930", day, day).candles().size());
                assertEquals(4, server.getRequestCount());
            }
        }
    }
    @Test void popularQuotesDoNotCacheACompleteProviderOutage() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var quote = mock(KisStockQuoteClient.class);
        when(quote.getStockQuote(anyString())).thenAnswer(inv -> new KisStockQuote(inv.getArgument(0),70000,1,calls.getAndIncrement()<5));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KisStockQuoteClient.class, () -> quote);
            context.register(ExternalCacheConfiguration.class, com.finsight.stock.PopularStockService.class);
            context.refresh();
            var service = context.getBean(com.finsight.stock.PopularStockService.class);
            assertTrue(service.getPopularStocks().stream().allMatch(com.finsight.stock.PopularStockView::fallback));
            assertTrue(service.getPopularStocks().stream().noneMatch(com.finsight.stock.PopularStockView::fallback));
            assertTrue(service.getPopularStocks().stream().noneMatch(com.finsight.stock.PopularStockView::fallback));
            assertEquals(10,calls.get());
        }
    }

}
