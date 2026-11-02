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
    @Test void missingNewsShowsCachedSameDayIndexWithoutClaimingACause() {
        var r = new AiDocentRequest("005930","삼성전자","2026-10-15T00:00:00",-9.48,"D",List.of(),"normal","코스피",-2.5);
        var text=ChartDocentService.rule(r).possibleReason();
        assertThat(text).contains("코스피","-2.50%","원인으로 단정하지");
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


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"POSITIVE,3,false","POSITIVE,-3,true","NEGATIVE,3,true","NEGATIVE,-3,false"})
    void directionDisagreementIsExplicit(String sentiment,double change,boolean opposite) {
        var r=new AiDocentRequest("005930","삼성전자","2026-11-02",change,"D",List.of(new AiDocentRequest.Article(1L,"실적 발표","영업이익 발표",Instant.now(),"DIRECT","보도",sentiment,.9)),"beginner","코스피",0.);
        var text=ChartDocentService.rule(r).possibleReason();
        if(opposite) assertThat(text).contains("소식에도 주가는","다른 요인").doesNotContain("받아들여졌을 가능성");
        else assertThat(text).contains("받아들여졌을 가능성");
    }
    @Test void marketDisagreementAndPriceSensitiveCacheKey() {
        var r=request("MARKET","D","beginner");
        var other=new AiDocentRequest(r.symbol(),r.symbolName(),r.date(),-3.25,r.period(),r.news(),r.level(),"코스피",2.);
        assertThat(ChartDocentService.rule(other).possibleReason()).contains("시장과 반대로");
        assertThat(ChartDocentService.cacheKey(other)).isNotEqualTo(ChartDocentService.cacheKey(r));
        var slight=new AiDocentRequest(r.symbol(),r.symbolName(),r.date(),3.254,r.period(),r.news(),r.level(),r.indexName(),r.indexChangePercent());
        assertThat(ChartDocentService.cacheKey(slight)).isEqualTo(ChartDocentService.cacheKey(r));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles={-.51,-.5,0,.5,.51})
    void neutralBandIncludesBoundariesBeforeSentimentAndMarketDirection(double change) {
        for(String relation:List.of("DIRECT","MARKET")) for(String sentiment:List.of("POSITIVE","NEGATIVE")) {
            var r=new AiDocentRequest("005930","삼성전자","2026-11-02",change,"D",List.of(new AiDocentRequest.Article(1L,"실적 발표","영업이익 발표",Instant.now(),relation,"보도",sentiment,.9)),"beginner","코스피",-1.2);
            var text=ChartDocentService.rule(r).possibleReason();
            if(Math.abs(change)<=.5) {
                assertThat(text).contains("DIRECT".equals(relation)?"소식이 나왔지만 주가는 거의 움직이지 않았어요":"시장이 움직인 날에도 주가는 거의 변하지 않았어요")
                    .doesNotContain("받아들여졌을 가능성","시장과 반대로","소식에도 주가는");
                assertThat(text).contains("DIRECT".equals(relation)?String.format(Locale.ROOT,"%+.2f%%",change):"코스피 -1.20%");
            } else {
                assertThat(text).doesNotContain("거의");
                boolean opposite="MARKET".equals(relation)?change>0:"POSITIVE".equals(sentiment)?change<0:change>0;
                assertThat(text).contains("MARKET".equals(relation)?(opposite?"시장과 반대로":"함께 움직였을"):(opposite?"소식에도 주가는":"받아들여졌을 가능성"));
            }
        }
    }
}
