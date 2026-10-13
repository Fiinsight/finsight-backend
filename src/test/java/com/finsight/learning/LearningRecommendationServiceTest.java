package com.finsight.learning;

import com.finsight.news.*;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.external.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;

class LearningRecommendationServiceTest {
    private News article(long id,String title,String term) {
        var item=mock(News.class);when(item.getId()).thenReturn(id);when(item.getTitle()).thenReturn(title);
        when(item.getRawContent()).thenReturn(title+" 내용을 확인했다.");when(item.getKeyTerms()).thenReturn(List.of(term));
        when(item.getPublishedAt()).thenReturn(Instant.parse("2026-10-12T00:00:00Z"));return item;
    }
    @Test void ownerActionsWeightsAndExclusionsReachAiAndUnknownReasonsAreRejected() {
        var jdbc=mock(JdbcTemplate.class);var news=mock(NewsRepository.class);var ai=mock(AiServiceClient.class);
        var service=new LearningRecommendationService(jdbc,news,ai,new ArticleContentExtractor());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(Instant.parse("2026-10-13T04:00:00Z"),ZoneId.of("Asia/Seoul")));
        News source=article(1,"환율 변화","환율"), candidate=article(2,"수출 기업 원화 매출","환율");
        when(jdbc.queryForList(contains("sum(weight)"),any(Object[].class))).thenReturn(List.of(Map.of("news_id",1L,"weight",1.8)));
        when(news.findAllById(any())).thenReturn(List.of(source));when(news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any())).thenReturn(List.of(source,candidate));
        when(ai.recommendations(any())).thenReturn(Optional.of(new AiRecommendationResponse("LOCAL_SENTENCE_TRANSFORMER",List.of(new AiRecommendationResponse.Item(2L,.9,1L,"spoofed"),new AiRecommendationResponse.Item(2L,.9,999L,"foreign")))));
        var result=service.get(7L,List.of(1L));
        var sent=org.mockito.ArgumentCaptor.forClass(AiRecommendationRequest.class);verify(ai).recommendations(sent.capture());
        assertEquals(2.1,sent.getValue().behaviors().getFirst().weight(),1e-9);
        assertTrue(sent.getValue().excludedIds().contains(1L));assertEquals(1,result.items().size());
        assertEquals(source.getTitle(),result.items().getFirst().reasonTitle());
        verify(jdbc).queryForList(contains("updated_at>=?"),eq(7L),any(),any(),eq(7L),any(),any(),eq(7L),any(),any());
        when(ai.recommendations(any())).thenReturn(Optional.empty());
        assertEquals("RULE_INTEREST",service.get(7L,List.of(1L)).basis());
    }
    @Test void coldStartDeduplicatesAndReadIdsStayBoundedAtController() {
        var jdbc=mock(JdbcTemplate.class);var news=mock(NewsRepository.class);var ai=mock(AiServiceClient.class);
        var service=new LearningRecommendationService(jdbc,news,ai,new ArticleContentExtractor());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(Instant.parse("2026-10-13T04:00:00Z"),ZoneId.of("Asia/Seoul")));
        var candidates=List.of(article(1,"국채 금리 하락","금리"),article(2,"국채 금리 하락","금리"),article(3,"반도체 생산 확대","반도체"));
        when(news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any())).thenReturn(candidates);
        assertTrue(service.get(7L,List.of()).items().isEmpty());verifyNoInteractions(ai);
        var recommendations=mock(LearningRecommendationService.class);
        var controller=new LearningController(mock(LearningService.class),mock(LearningProgressService.class),recommendations);
        controller.recommendations(7L,"1,1,2");verify(recommendations).get(7L,List.of(1L,2L));
        for(String ids:List.of("0","-1","x","1,","1".repeat(4001))) assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.recommendations(7L,ids));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.recommendations(null,""));
        assertTrue(LearningRecommendationService.sameEvent("삼성전자 분기 실적 발표","삼성전자 분기 실적 발표"));
        assertFalse(LearningRecommendationService.sameEvent("삼성전자 분기 실적 발표","원화 환율 변동"));
    }
    @Test void recommendationReasonsDescribeTheActualActionAndTruncateLongTitles() {
        var source=article(1,"한국은행 기준금리 변화와 금융 시장 영향","금리");
        assertEquals("'금리' 퀴즈를 틀린 기사와 비슷해요",LearningRecommendationService.reasonText(source,new LearningRecommendationService.Activity("QUIZ","금리")));
        String title=source.getTitle().substring(0,15)+"…";
        assertEquals("메모한 '"+title+"'와 비슷해요",LearningRecommendationService.reasonText(source,new LearningRecommendationService.Activity("MEMO",null)));
        assertEquals("판단한 '"+title+"'와 비슷해요",LearningRecommendationService.reasonText(source,new LearningRecommendationService.Activity("JUDGEMENT",null)));
        var shortTitle=article(2,"환율 변화","환율");
        assertEquals("읽은 '환율 변화'와 비슷해요",LearningRecommendationService.reasonText(shortTitle,null));
    }
}
