package com.finsight.chart;

import com.finsight.config.ExternalCacheConfiguration;
import com.finsight.external.kis.*;
import com.finsight.news.NewsRepository;
import com.finsight.news.collect.ArticleContentExtractor;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChartReadingCacheTest {
    @Configuration @Import(ExternalCacheConfiguration.class) static class Config {
        @Bean KisDailyCandleClient daily() {
            var client = mock(KisDailyCandleClient.class);
            when(client.getCandlesWithStatus(anyString(), anyInt(), anyString())).thenReturn(
                    new KisDailyCandleResult(List.of(
                            new KisDailyCandle(LocalDate.of(2026,10,26),100,100,100,100),
                            new KisDailyCandle(LocalDate.of(2026,10,27),105,105,105,105)), false));
            return client;
        }
        @Bean NewsRepository news() {
            var repo = mock(NewsRepository.class);
            when(repo.findByPublishedAtBetween(any(), any())).thenReturn(List.of());
            return repo;
        }
        @Bean ChartDocentService docents() {
            var service = mock(ChartDocentService.class);
            when(service.get(any())).thenAnswer(call -> ChartDocentService.rule(call.getArgument(0)));
            return service;
        }
        @Bean ChartService charts(KisDailyCandleClient daily, NewsRepository news) {
            return new ChartService(daily, null, news, new ArticleContentExtractor(), null);
        }
    }

    @Test void repeatRequestSkipsNewsAndDocentWorkButUsesTheLatestCachedQuote() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = new ChartController(context.getBean(ChartService.class));
            var first = controller.chart("005930", "D", 5, "beginner", null, new MockHttpServletResponse());
            var cache = context.getBean(CacheManager.class);
            var readings = (com.github.benmanes.caffeine.cache.Cache<?,?>) cache.getCache("chartReadings").getNativeCache();
            assertThat(readings.policy().expireAfterWrite().orElseThrow().getExpiresAfter(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(60);
            cache.getCache("kisStockQuotes").put("005930", new KisStockQuote("005930", 107, 7, false));
            var response = new MockHttpServletResponse();
            var second = controller.chart("005930", "D", 15, "beginner", null, response);
            assertThat(second.moveInsights()).isEqualTo(first.moveInsights());
            assertThat(second.price()).isEqualTo(107);
            assertThat(second.changePercent()).isEqualTo(7);
            verify(target(context, KisDailyCandleClient.class), times(1)).getCandlesWithStatus("005930",30,"D");
            verify(target(context, NewsRepository.class), times(2)).findByPublishedAtBetween(any(), any());
            verify(target(context, ChartDocentService.class), times(1)).get(any());
            assertThat(response.getHeader("Server-Timing")).contains("chart;dur=", "news_docent;dur=", "cached_quote;dur=");
        }
    }

    @Test void levelsAndChangedCandlesDoNotShareReadingsAndExpiryAllowsFreshNews() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var charts = context.getBean(ChartService.class);
            var chart = charts.getChart("005930", "D", 5);
            charts.withDocents(chart, "beginner");
            charts.withDocents(chart, "normal");
            var changed = new ChartResponse(chart.symbol(), chart.price(), chart.changePercent(),
                    List.of(chart.candles().getFirst(), new ChartResponse.CandleView(LocalDate.of(2026,10,27),110,110,110,110)),
                    chart.newsMarkers(), chart.relatedNews(), chart.docent(), chart.period(), chart.intervalMinutes(),
                    chart.minuteCandles(), false, chart.moveInsights());
            charts.withDocents(changed, "beginner");
            // Simulate expiration without waiting a minute in the test.
            context.getBean(CacheManager.class).getCache("chartReadings").clear();
            charts.withDocents(chart, "beginner");
            verify(target(context, NewsRepository.class), times(5)).findByPublishedAtBetween(any(), any());
            verify(target(context, ChartDocentService.class), times(3)).get(any());
        }
    }

    @Test void fallbackIsNeverStoredAsAReading() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var chart = new ChartResponse("005930",0,0,List.of(),List.of(),List.of(),null,"D",null,List.of(),true,List.of());
            var charts = context.getBean(ChartService.class);
            charts.withDocents(chart, "beginner");
            var nativeCache = (com.github.benmanes.caffeine.cache.Cache<?,?>) context.getBean(CacheManager.class).getCache("chartReadings").getNativeCache();
            assertThat(nativeCache.estimatedSize()).isZero();
        }
    }

    private static <T> T target(AnnotationConfigApplicationContext context, Class<T> type) {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(type));
    }
}
