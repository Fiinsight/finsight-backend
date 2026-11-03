package com.finsight.chart;

import com.finsight.config.ExternalCacheConfiguration;
import com.finsight.external.kis.*;
import com.finsight.news.NewsRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChartCandlesTest {
    @Configuration @Import(ExternalCacheConfiguration.class)
    static class Config {
        @Bean KisDailyCandleClient daily() { return mock(KisDailyCandleClient.class); }
        @Bean KisMinuteCandleClient minute() { return mock(KisMinuteCandleClient.class); }
        @Bean NewsRepository news() { return mock(NewsRepository.class); }
        @Bean ChartService service(KisDailyCandleClient daily, KisMinuteCandleClient minute, NewsRepository news) {
            return new ChartService(daily, minute, news, null, null);
        }
    }

    @Test void candlesRenderEvenWhenNewsIsUnavailableAndDailyIntervalsShareCache() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            KisDailyCandleClient daily = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(KisDailyCandleClient.class));
            when(daily.getCandlesWithStatus("005930", 30, "D")).thenReturn(new KisDailyCandleResult(
                    List.of(new KisDailyCandle(LocalDate.of(2026, 11, 2), 100, 110, 90, 105)), false));
            var service = context.getBean(ChartService.class);
            assertThat(service.getCandles("005930", "D", 5).candles()).hasSize(1);
            assertThat(service.getCandles("005930", "D", 15).candles()).hasSize(1);
            verify(daily).getCandlesWithStatus("005930", 30, "D");
            verifyNoInteractions(context.getBean(NewsRepository.class));
        }
    }

    @Test void fallbackIsCachedForThirtySecondsThenCanRecover() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            KisDailyCandleClient daily = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(KisDailyCandleClient.class));
            when(daily.getCandlesWithStatus("005930", 30, "D")).thenReturn(
                    new KisDailyCandleResult(List.of(), true), new KisDailyCandleResult(List.of(), false));
            var service = context.getBean(ChartService.class);
            assertThat(service.getCandles("005930", "D", 5).fallback()).isTrue();
            assertThat(service.getCandles("005930", "D", 5).fallback()).isTrue();
            verify(daily).getCandlesWithStatus("005930", 30, "D");
            var cache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) context.getBean(org.springframework.cache.CacheManager.class)
                    .getCache("chartCandles").getNativeCache();
            assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(java.time.Duration.ofSeconds(30));
            cache.policy().expireAfterWrite().orElseThrow().setExpiresAfter(java.time.Duration.ZERO);
            assertThat(service.getCandles("005930", "D", 5).fallback()).isFalse();
            verify(daily, times(2)).getCandlesWithStatus("005930", 30, "D");
        }
    }
}
