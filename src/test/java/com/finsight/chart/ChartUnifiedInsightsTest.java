package com.finsight.chart;

import com.finsight.external.kis.*;
import com.finsight.news.*;
import com.finsight.news.collect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChartUnifiedInsightsTest {
    @Test void levelsReuseTheSameCleanCandidatesClosingTimeAndPricesWithoutExtraIo() throws Exception {
        var daily=mock(KisDailyCandleClient.class);var repository=mock(NewsRepository.class);
        var candles=List.of(new KisDailyCandle(LocalDate.parse("2026-11-02"),100,100,100,100),new KisDailyCandle(LocalDate.parse("2026-11-03"),96,96,96,96));
        when(daily.getCandlesWithStatus(anyString(),anyInt(),anyString())).thenReturn(new KisDailyCandleResult(candles,false));
        var article=new News(1L,"삼성전자, 수출 우려에 4% 하락 마감","https://fixture.test/report","합성",Instant.parse("2026-11-03T06:40:00Z"),"시장","005930","",("삼성전자는 이날 수출 우려에 4% 하락 마감했다. ").repeat(10));
        ChartNewsRows.stub(repository,List.of(article));
        var service=new ChartService(daily,null,repository,new ArticleContentExtractor(),new NewsSymbolMatcher());
        var base=service.getChart("005930","D",5);
        assertThat(base.moveInsights()).hasSize(1);
        var point=base.moveInsights().getFirst();
        assertThat(point.newsId()).isEqualTo(1L);
        assertThat(point.evidence().date()).endsWith("15:30:00");
        assertThat(point.evidence().news().getFirst().afterCloseReport()).isTrue();
        assertThat(point.candidateNews()).allSatisfy(n -> assertThat(n.relationType()).isEqualTo("REFERENCE"));
        clearInvocations(repository,daily);
        for(String level:List.of("beginner","normal","analyst")) {
            var rendered=service.withDocents(base,level);
            assertThat(rendered.candles()).isSameAs(base.candles());
            assertThat(rendered.moveInsights().getFirst().candidateNews()).isSameAs(point.candidateNews());
            assertThat(rendered.moveInsights().getFirst().newsId()).isEqualTo(1L);
            assertThat(rendered.moveInsights().getFirst().evidence().level()).isEqualTo(level);
            assertThat(rendered.moveInsights().getFirst().indexChangePercent()).isEqualTo(point.indexChangePercent());
        }
        verifyNoInteractions(repository,daily);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(base)).path("moveInsights").get(0).has("evidence")).isFalse();
    }
    @Test void insightsAndLegacyRouteResolveTheSameLevelAndValidateIt() {
        var charts=mock(ChartService.class);
        var base=new ChartResponse("005930",0,0,List.of(),List.of(),List.of(),null,"D",null,List.of(),false,List.of());
        when(charts.getChart("005930","D",5)).thenReturn(base);
        when(charts.withCurrentQuote(base)).thenReturn(base);
        when(charts.withDocents(eq(base),anyString())).thenReturn(base);
        var controller=new ChartController(charts,new ChartInsightsJson(charts,new com.fasterxml.jackson.databind.ObjectMapper()));
        controller.insights("005930","D",5,"analyst",null);
        controller.chart("005930","D",5,"analyst",null);
        verify(charts,times(2)).withDocents(base,"analyst");
        controller.insights("005930","D",5,null,null);
        verify(charts).withDocents(base,"normal");
        assertThatThrownBy(() -> controller.insights("005930","D",5,"invalid",null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void disabledAiNeverMakesAnHttpCallAndCacheKeysTrackSourceEdits() {
        var ai=mock(com.finsight.external.AiServiceClient.class);
        var service=new ChartDocentService(ai);
        var r=new com.finsight.external.AiDocentRequest("005930","삼성전자","2026-11-03T15:30:00",-4,"D",List.of(new com.finsight.external.AiDocentRequest.Article(1L,"삼성전자 실적 발표","삼성전자는 실적을 발표했다.",Instant.parse("2026-11-03T03:00:00Z"),"DIRECT","합성")),"normal","코스피",-1.0);
        assertThat(service.get(r).mode()).isEqualTo("RULE");verifyNoInteractions(ai);
        var changed=new com.finsight.external.AiDocentRequest(r.symbol(),r.symbolName(),r.date(),r.changePercent(),r.period(),List.of(new com.finsight.external.AiDocentRequest.Article(1L,"삼성전자 실적 발표","삼성전자는 영업이익을 발표했다.",Instant.parse("2026-11-03T03:00:00Z"),"DIRECT","합성")),r.level(),r.indexName(),r.indexChangePercent());
        assertThat(ChartDocentService.cacheKey(changed)).isNotEqualTo(ChartDocentService.cacheKey(r));
    }
    @Test void largestMovesAreRenderedFirstButResponseOrderIsPreserved() {
        var renderer=mock(ChartDocentService.class);
        var observed=new ArrayList<Double>();
        when(renderer.getBatch(anyList())).thenAnswer(call -> {
            List<com.finsight.external.AiDocentRequest> requests=call.getArgument(0);
            requests.forEach(r -> observed.add(r.changePercent()));return requests.stream().map(ChartDocentService::rule).toList();
        });
        var service=new ChartService(null,null,mock(NewsRepository.class),new ArticleContentExtractor(),null);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"docentService",renderer);
        var at=LocalDateTime.parse("2026-11-02T15:30:00");
        var moves=List.of(2.,-7.,4.).stream().map(change -> new ChartResponse.MoveInsightView(at,change,null,null,null,"",0,List.of(),null)).toList();
        var chart=new ChartResponse("005930",100,2,moves.stream().map(m -> new ChartResponse.CandleView(at.toLocalDate(),100,100,100,100)).toList(),List.of(),List.of(),null,"D",null,List.of(),false,moves);
        assertThat(service.withDocents(chart,"normal").moveInsights()).extracting(ChartResponse.MoveInsightView::changePercent).containsExactly(2.,-7.,4.);
        assertThat(observed).containsExactly(-7.,4.,2.);
        verify(renderer,times(1)).getBatch(anyList());
    }

}
