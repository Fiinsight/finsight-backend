package com.finsight.chart;
import com.finsight.news.*;import java.util.*;import java.time.*;import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;import static org.assertj.core.api.Assertions.*;
class ChartNewsBudgetTest {
    @Test void allLegacyMoveExplanationsShareOneThreeSecondAiBudget() {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var ai=new com.finsight.external.AiServiceClient(org.springframework.web.reactive.function.client.WebClient.builder()
                .exchangeFunction(request->{requests.incrementAndGet();return reactor.core.publisher.Mono.never();}),"http://127.0.0.1");
        var repository=mock(NewsRepository.class);Instant at=Instant.parse("2026-11-02T03:00:00Z");
        var article=new News(1L,"삼성전자 영업이익 증가","https://fixture.test","TEST",at,"기업","005930","영업이익","삼성전자 영업이익이 증가했다.");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(article));
        var service=new ChartService(null,null,repository,new com.finsight.news.collect.ArticleContentExtractor(),new com.finsight.news.collect.NewsSymbolMatcher());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"docentService",new ChartDocentService(ai));
        var marker=new ChartResponse.NewsMarkerView(java.time.LocalDate.of(2026,11,2),at,1L,article.getTitle(),"TEST","DIRECT");
        var move=new ChartResponse.MoveInsightView(java.time.LocalDateTime.of(2026,11,2,15,30),3,1L,article.getTitle(),"TEST","근거",1,List.of(marker),null);
        var chart=new ChartResponse("005930",100,3,List.of(),List.of(),List.of(),null,"D",null,List.of(),false,java.util.Collections.nCopies(10,move));
        long started=System.nanoTime();var result=service.withDocents(chart,"normal");
        assertThat((System.nanoTime()-started)/1_000_000).isBetween(2500L,4500L);
        assertThat(requests.get()).isEqualTo(1);assertThat(result.moveInsights()).hasSize(10).allSatisfy(m->assertThat(m.docent()).isNotNull());
        verify(repository,times(1)).findAllById(List.of(1L));verify(repository,never()).findById(any());
    }

    @Test void boundedMetadataPagingRetainsAnAliasAtTheEndAndLoadsOnlyMatchedBodies() {
        var repository=mock(NewsRepository.class);var rows=new ArrayList<News>();
        Instant at=Instant.parse("2026-11-02T03:00:00Z");
        for(long id=1;id<=105;id++) rows.add(new News(id,id==105?"삼전 실적 발표":"지방 축제 안내","https://fixture.test/"+id,"TEST",at,"정책",null,"","세부 안내"));
        ChartNewsRows.stub(repository,rows);
        var service=new ChartService(null,null,repository,null,null);
        List<News> found=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"findRelatedNews","005930",at.minusSeconds(1),at.plusSeconds(1));
        assertThat(found).extracting(News::getId).containsExactly(105L);
        verify(repository,times(2)).findChartCandidatesAfterId(any(),any(),anyLong(),argThat(p->p.getPageSize()==100));
        verify(repository).findAllById(List.of(105L));
        verify(repository,never()).findByPublishedAtBetween(any(),any());
    }
}
