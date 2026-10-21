package com.finsight.chart;

import com.finsight.external.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChartDocentTest {
    private static AiDocentRequest request(String relation, String period, String level) {
        var news = "NONE".equals(relation) ? List.<AiDocentRequest.Article>of() : List.of(new AiDocentRequest.Article(1L, "삼성전자 호실적 발표", "영업이익이 12.5% 증가했다. 다음 사실입니다.", Instant.parse("2026-10-07T00:00:00Z"), relation, "보도"));
        return new AiDocentRequest("005930", "삼성전자", "2026-10-07T00:00:00", 3.25, period, news, level, "코스피", null);
    }
    @Test void rulesCoverThreeRelationsWithoutCausalClaims() {
        for (String relation : List.of("DIRECT", "MARKET", "NONE")) {
            var result = ChartDocentService.rule(request(relation, "D", "beginner"));
            assertThat(result.relation()).isEqualTo(relation);
            assertThat(result.mode()).isEqualTo("RULE");
            assertThat(result.possibleReason()).doesNotContain("때문에").containsAnyOf("수 있어요", "가능성이 있어요");
            assertThat(result.whatHappened()).isNotBlank();
            assertThat(result.marketImpact()).isNotBlank();
            if ("DIRECT".equals(relation)) assertThat(result.possibleReason()).contains("+3.25%", "호재");
            if ("MARKET".equals(relation)) assertThat(result.possibleReason()).doesNotContain("%");
        }
    }
    @Configuration @EnableCaching static class Config {
        @Bean ConcurrentMapCacheManager cacheManager() { return new ConcurrentMapCacheManager("chartDocents"); }
        @Bean AiServiceClient ai() { return mock(AiServiceClient.class); }
        @Bean ChartDocentService docent(AiServiceClient ai) { return new ChartDocentService(ai); }
    }
    @Test void cacheReusesPointAndSeparatesPeriodAndLevel() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var ai = context.getBean(AiServiceClient.class);
            when(ai.docent(any())).thenAnswer(call -> Optional.of(ChartDocentService.rule(call.getArgument(0))));
            var service = context.getBean(ChartDocentService.class);
            var request = request("DIRECT", "D", "beginner");
            assertThat(service.get(request)).isSameAs(service.get(request));
            service.get(request("DIRECT", "W", "beginner"));
            service.get(request("DIRECT", "D", "analyst"));
            service.get(request("DIRECT", "MINUTE", "beginner"));
            service.get(request("NONE", "D", "beginner"));
            verify(ai, times(3)).docent(any());
        }
    }
    @Test void largestPointUsesItsExactDocentAndLegacyAlias() throws Exception {
        var small = ChartDocentService.rule(request("DIRECT", "D", "normal"));
        var largest = ChartDocentService.rule(request("NONE", "D", "normal"));
        var moves = List.of(new ChartResponse.MoveInsightView(LocalDateTime.now(), 3.25, 1L, "제목", "보도", "", 0.8, List.of(), small), new ChartResponse.MoveInsightView(LocalDateTime.now(), -6, null, null, null, "", 0, List.of(), largest));
        assertThat(ChartService.largestDocent(moves)).isSameAs(largest);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(small));
        assertThat(json.get("whyItMoved").asText()).isEqualTo(json.get("possibleReason").asText());
    }
    @Test void titleSentimentIsTransientAndSummaryNeverCutsFigures() {
        assertThat(ChartDocentService.titleSentiment("삼성전자 호실적 상향")).isEqualTo(com.finsight.briefing.SentimentHint.POSITIVE);
        assertThat(ChartDocentService.titleSentiment("규제 강화로 부진")).isEqualTo(com.finsight.briefing.SentimentHint.NEGATIVE);
        assertThat(ChartDocentService.titleSentiment("급등 뒤 급락")).isEqualTo(com.finsight.briefing.SentimentHint.NEGATIVE);
        assertThat(ChartService.docentSummary("첫 사실이다. " + "가".repeat(390) + " 123.45% 증가했다.")).isEqualTo("첫 사실이다.");
    }
    @Test void malformedAiResultFallsBack() {
        var ai = mock(AiServiceClient.class);
        when(ai.docent(any())).thenReturn(Optional.of(new ChartResponse.DocentView("MARKET", "a", "b", "c", 1L, "", "", "LLM")));
        assertThat(new ChartDocentService(ai).get(request("DIRECT", "D", "normal")).mode()).isEqualTo("RULE");
    }
    @Test void marketIndexUsesOnlyRealCachedDailyCloses() {
        var cache = new org.springframework.cache.concurrent.ConcurrentMapCache("kisDailyCandles");
        var day = LocalDate.of(2026, 10, 7);
        var data = new com.finsight.external.kis.KisDailyCandleResult(List.of(
            new com.finsight.external.kis.KisDailyCandle(day.minusDays(1), 100, 100, 100, 100),
            new com.finsight.external.kis.KisDailyCandle(day, 98.8, 98.8, 98.8, 98.8)), false);
        assertThat(ChartService.cachedIndexChange(cache, "KOSPI", day)).isNull();
        cache.put("close:KOSPI:2026-10-06:2026-10-07", data);
        assertThat(ChartService.cachedIndexChange(cache, "KOSPI", day)).isEqualTo(-1.2);
        assertThat(ChartService.cachedIndexChange(cache, "KOSDAQ", day)).isNull();
        cache.clear();
        cache.put("KOSPI:30:D", new com.finsight.external.kis.KisDailyCandleResult(data.candles(), true));
        assertThat(ChartService.cachedIndexChange(cache, "KOSPI", day)).isNull();
    }

}
