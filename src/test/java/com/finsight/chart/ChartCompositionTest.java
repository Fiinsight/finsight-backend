package com.finsight.chart;

import com.finsight.external.kis.*;
import com.finsight.news.*;
import com.finsight.news.collect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChartCompositionTest {
    @Test void scoreCacheIsContentSensitiveAndKeepsDirectionAdjustments() {
        Instant at=Instant.parse("2026-11-02T03:00:00Z");
        var positive=new News(1L,"삼성전자 실적 상승","https://fixture.test/score","TEST",at,"기업","005930","","삼성전자 영업이익이 증가했다.");
        var negative=new News(1L,"삼성전자 실적 하락","https://fixture.test/score","TEST",at,"기업","005930","","삼성전자 영업이익이 감소했다.");
        assertEquals(12,ChartService.representativeScore("005930",positive,3));
        assertEquals(7,ChartService.representativeScore("005930",positive,-3));
        assertEquals(9,ChartService.representativeScore("005930",positive,0));
        assertEquals(12,ChartService.representativeScore("005930",negative,-3));
        assertEquals(7,ChartService.representativeScore("005930",negative,3));
    }
    @Test void integratedDailyResponseReusesPriceOnlyCacheAndKeepsEveryJsonField() throws Exception {
        var daily=mock(KisDailyCandleClient.class); var minute=mock(KisMinuteCandleClient.class);
        var repository=mock(NewsRepository.class);
        var caches=new ConcurrentMapCacheManager("chartCandles","popularStockQuotes","kisStockQuotes");
        var candles=List.of(new ChartResponse.CandleView(LocalDate.of(2026,11,10),100,101,99,100));
        var series=new ChartResponse("005930",0,0,candles,List.of(),List.of(),null,"D",null,List.of(),false,List.of());
        caches.getCache("chartCandles").put("005930:D:0",series);
        var service=new ChartService(daily,minute,repository,new ArticleContentExtractor(),new NewsSymbolMatcher());
        ReflectionTestUtils.setField(service,"cacheManager",caches);
        var actual=service.getChart("005930","D",5);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var expected=mapper.readTree("""
          {"symbol":"005930","symbolName":"삼성전자","price":0.0,"changePercent":0.0,
           "candles":[{"date":[2026,11,10],"open":100.0,"high":101.0,"low":99.0,"close":100.0}],
           "newsMarkers":[],"relatedNews":[],"docent":null,"period":"D","intervalMinutes":null,
           "minuteCandles":[],"fallback":false,"moveInsights":[]}
          """);
        assertEquals(expected,mapper.valueToTree(actual));
        verifyNoInteractions(daily,minute);
    }

    @Test void denseFirstPageSwitchesToOneFullQueryButRetainsLaterAliasesAndRejectsUnrelatedRows() {
        var repository=mock(NewsRepository.class);var rows=new ArrayList<News>();
        Instant at=Instant.parse("2026-11-02T03:00:00Z");
        for(long id=1;id<=102;id++) rows.add(new News(id,id==101?"삼전 실적 발표":id==102?"지방 축제":"삼성전자 실적", "https://fixture.test/"+id,"TEST",at,"기업",id<=100?"005930":null,"","세부 안내"));
        ChartNewsRows.stub(repository,rows);
        when(repository.findByPublishedAtBetween(any(),any())).thenReturn(rows);
        var service=new ChartService(null,null,repository,null,null);
        List<News> found=ReflectionTestUtils.invokeMethod(service,"findRelatedNews","005930",at.minusSeconds(1),at.plusSeconds(1));
        assertEquals(101,found.size());
        assertTrue(found.stream().anyMatch(n -> n.getId()==101L));
        verify(repository,times(1)).findChartCandidatesAfterId(any(),any(),anyLong(),any());
        verify(repository,times(1)).findByPublishedAtBetween(any(),any());
        verify(repository,never()).findAllById(any());
    }
}
