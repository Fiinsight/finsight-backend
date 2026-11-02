package com.finsight.chart;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.finsight.external.kis.*;
import java.util.List;
class ChartCacheTest {
 @Configuration @EnableCaching static class Config {
  @Bean ConcurrentMapCacheManager cacheManager() { return new ConcurrentMapCacheManager("chartResponses", "kisStockQuotes", "popularStockQuotes", "kisDailyCandles"); }
  @Bean KisDailyCandleClient daily() { var client = mock(KisDailyCandleClient.class); when(client.getCandlesWithStatus(anyString(),anyInt(),anyString())).thenReturn(new KisDailyCandleResult(List.of(), false)); return client; }
  @Bean ChartService service(KisDailyCandleClient daily) { return new ChartService(daily, null, null, null, null); }
 }
 @Test void failureDoesNotPreventARecoveredChartBeforeCacheExpiry() {
  try (var context = new AnnotationConfigApplicationContext(Config.class)) {
   KisDailyCandleClient daily = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(KisDailyCandleClient.class));
   when(daily.getCandlesWithStatus("005930",30,"D")).thenReturn(new KisDailyCandleResult(List.of(),true),new KisDailyCandleResult(List.of(),false));
   var service = context.getBean(ChartService.class);
   assertThat(service.getChart("005930","D",5).fallback()).isTrue();
   assertThat(service.getChart("005930","D",5).fallback()).isFalse();
   assertThat(service.getChart("005930","D",5).fallback()).isFalse();
   verify(daily,times(2)).getCandlesWithStatus("005930",30,"D");
  }
 }
 @Test void dailyIntervalsShareOneCacheEntry() {
  try (var context = new AnnotationConfigApplicationContext(Config.class)) {
   var service = context.getBean(ChartService.class);
   service.getChart("005930", "D", 5); service.getChart("005930", "D", 15); service.getChart("005930", "D", 1);
   verify((KisDailyCandleClient) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(KisDailyCandleClient.class)),times(1)).getCandlesWithStatus("005930",30,"D");
   assertThat(((java.util.Map<?,?>)context.getBean(ConcurrentMapCacheManager.class).getCache("chartResponses").getNativeCache())).hasSize(1);
  }
 }
}
