package com.finsight.chart;
import org.junit.jupiter.api.Test;
import com.finsight.external.*;
import java.util.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChartAiGuardTest {
 @Test void sectorCanUseAnOptedInMockButCannotChangeSourceOrInventNumbers() {
  var ai=mock(AiServiceClient.class);var service=new ChartDocentService(ai);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"withLlm",true);
  var request=new AiDocentRequest("005930","삼성전자","2026-11-02",-4.8,"D",List.of(new AiDocentRequest.Article(1L,"반도체 수출 우려에 하락","반도체 수출은 12% 감소했다.",Instant.EPOCH,"SECTOR","합성")),"normal","코스피",-1.2);
  var valid=new ChartResponse.DocentView("SECTOR","반도체 수출은 12% 감소했어요.","수출 우려가 영향을 줬을 수 있어요.","시장 대비 초과 등락률은 -3.60%p예요.",1L,"제목","합성","LLM");
  when(ai.docent(any(),any())).thenReturn(Optional.of(valid));assertThat(service.get(request).mode()).isEqualTo("LLM");
  for(var bad:List.of(new ChartResponse.DocentView("SECTOR","반도체 수출은 999% 감소했어요.",valid.possibleReason(),valid.marketImpact(),1L,"","","LLM"),
    new ChartResponse.DocentView("SECTOR",valid.whatHappened(),"수출 때문에 하락했습니다.",valid.marketImpact(),1L,"","","LLM"),
    new ChartResponse.DocentView("SECTOR",valid.whatHappened(),valid.possibleReason(),valid.marketImpact(),2L,"","","LLM"))) {
   when(ai.docent(any(),any())).thenReturn(Optional.of(bad));assertThat(service.get(request).mode()).isEqualTo("RULE");
  }
 }
 @Test void termsComeOnlyFromTheRegisteredDictionaryAndAreLimitedToThree() {
  var article=new com.finsight.news.News(1L,"삼성전자 영업이익·공매도 발표","https://fixture.test","합성",Instant.EPOCH,"시장","005930","",("삼성전자는 영업이익과 공매도, 유동성, 환율 자료를 발표했다. ").repeat(5));
  var service=new ChartService(null,null,null,new com.finsight.news.collect.ArticleContentExtractor(),null);
  var dictionary=mock(com.finsight.term.TermRepository.class);
  when(dictionary.findAll()).thenReturn(List.of(new com.finsight.term.Term("영업이익","본업에서 얻은 이익입니다."),new com.finsight.term.Term("환율","교환 비율입니다.")));
  org.springframework.test.util.ReflectionTestUtils.setField(service,"termRepository",dictionary);
  var request=(AiDocentRequest)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"docentRequest","005930",LocalDateTime.parse("2026-11-02T15:30:00"),-3.,"MINUTE",List.of(article),"normal");
  assertThat(request.terms()).hasSizeBetween(1,3).allSatisfy(t -> {assertThat(t.definition()).isNotBlank();assertThat(t.term()).isIn("영업이익","공매도","유동성","환율");});
  assertThat(ChartDocentService.rule(request).terms()).isEqualTo(request.terms());
 }
 @Test void textUsesTheSameTwoDecimalStockReturnAsTheExistingMoveField() {
  var service=new ChartService(null,null,null,new com.finsight.news.collect.ArticleContentExtractor(),null);
  var r=(AiDocentRequest)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"docentRequest","005930",LocalDateTime.parse("2026-11-02T15:30:00"),-3.215,"MINUTE",List.of(),"normal");
  assertThat(r.changePercent()).isEqualTo(Math.round(-3.215*100.0)/100.0);
  assertThat(ChartDocentService.rule(r).possibleReason()).contains("-3.21%");
 }

}
