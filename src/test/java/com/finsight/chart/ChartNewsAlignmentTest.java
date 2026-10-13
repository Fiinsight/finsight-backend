package com.finsight.chart;

import com.finsight.news.News;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.external.AiDocentRequest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChartNewsAlignmentTest {
    List<News> fixture() throws Exception {
        var result=new ArrayList<News>();
        var rows=new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream("/chart-news-october-regression.json"));
        for(var row:rows) {
            var n=new News(row.get("title").asText(),row.get("url").asText(),row.get("source").asText(),OffsetDateTime.parse(row.get("published_at").asText()).toInstant(),row.get("raw_content").asText());
            org.springframework.test.util.ReflectionTestUtils.setField(n,"id",row.get("id").asLong());
            n.setRelatedSymbol(row.get("related_symbol").isNull()?null:row.get("related_symbol").asText());result.add(n);
        }
        return result;
    }
    List<News> select(String symbol,String date,double change,List<News> articles) {
        var day=LocalDate.parse(date);var zone=ZoneId.of("Asia/Seoul");
        return ChartService.candidateNews(symbol,articles,day.atStartOfDay(zone).toInstant(),day.plusDays(1).atTime(9,0).atZone(zone).toInstant(),change);
    }
    @Test void samsungOctober12PrefersDeclineAndIncludesIndexAliases() throws Exception {
        var chosen=select("005930","2026-10-12",-4.39,fixture());
        assertThat(chosen.getFirst().getId()).isIn(232L,264L);
        assertThat(chosen).extracting(News::getId).contains(232L).doesNotContain(295L);
        var lead=new ArticleContentExtractor().eventLead(chosen.getFirst().getTitle(),chosen.getFirst().getRawContent(),LocalDate.parse("2026-10-12"),-4.39);
        assertThat(lead).doesNotContain("지난해", "HBM4 실물", "사진").containsAnyOf("하락","급락","낙폭");
    }
    @Test void samsungOctober13DoesNotPickPositiveMemoryStory() throws Exception {
        assertThat(select("005930","2026-10-13",-8.08,fixture()).getFirst().getId()).isEqualTo(324L);
    }
    @Test void exhibitionAloneProducesNone() throws Exception {
        var exhibition=fixture().stream().filter(n->n.getId()==308L).toList();
        assertThat(select("000660","2026-10-13",-8.47,exhibition)).isEmpty();
        var r=new AiDocentRequest("000660","SK하이닉스","2026-10-13",-8.47,"D",List.of(),"normal",null,null);
        assertThat(ChartDocentService.rule(r).relation()).isEqualTo("NONE");
        assertThat(ChartDocentService.rule(r).whatHappened()).contains("직접 설명하는 뉴스를 찾지 못했어요");
    }
    @Test void photoNoticeAndInlineCreditAreRemovedWithoutDatabaseEdit() throws Exception {
        var n=fixture().stream().filter(a->a.getId()==333L).findFirst().orElseThrow();
        var cleaned=new ArticleContentExtractor().clean(n.getTitle(),n.getRawContent());
        assertThat(cleaned).startsWith("인공지능(AI) 열풍으로 메모리 반도체 가격이 치솟으면서");
        assertThat(n.getRawContent()).startsWith("사진은 기사와 관련 없음.");
    }
    @Test void cachedCandlesAreRescoredWithoutAnyMarketClientCall() throws Exception {
        var repo=mock(com.finsight.news.NewsRepository.class);
        when(repo.findByPublishedAtBetween(any(),any())).thenReturn(fixture());
        for(var n:fixture()) when(repo.findById(n.getId())).thenReturn(Optional.of(n));
        var daily=mock(com.finsight.external.kis.KisDailyCandleClient.class);
        var minute=mock(com.finsight.external.kis.KisMinuteCandleClient.class);
        var ai=mock(com.finsight.external.AiServiceClient.class);
        var service=new ChartService(daily,minute,repo,new ArticleContentExtractor(),new com.finsight.news.collect.NewsSymbolMatcher());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"docentService",new ChartDocentService(ai));
        // Synthetic unit-test prices only; the test never persists them or calls a price API.
        var candles=List.of(new ChartResponse.CandleView(LocalDate.parse("2026-10-08"),100,100,100,100),new ChartResponse.CandleView(LocalDate.parse("2026-10-12"),95.61,95.61,95.61,95.61));
        var cached=new ChartResponse("005930",95.61,-4.39,candles,List.of(),List.of(),null,"D",null,List.of(),false,List.of());
        assertThat(service.withDocents(cached,"normal").moveInsights().getFirst().newsId()).isIn(232L,264L);
        verifyNoInteractions(daily,minute);
    }
}
