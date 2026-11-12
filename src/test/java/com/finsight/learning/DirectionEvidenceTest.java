package com.finsight.learning;
import org.junit.jupiter.api.Test;
import java.util.*;
import com.finsight.term.Term;
import static org.assertj.core.api.Assertions.*;
class DirectionEvidenceTest {
 private final List<Term> dictionary=List.of(new Term("금리","이자"),new Term("매출","판매 금액"),new Term("환율","교환 비율"));
 @Test void directionsRequireNearbyObservedMovementOfTheSameSubject() {
  for(String text:List.of("금리 동결 속 매출은 3% 증가했다.","금리는 2% 증가하지 않았다.","금리는 2% 올라서지 못했다.","금리는 2% 상승에 그쳤다.","금리 전망 속 삼성전자는 2% 상승했다.","금리 관련 발표에서 다른회사는 2% 상승했다.","금리는 매우 긴 설명과 여러 상황을 모두 검토한 뒤 2% 상승했다."))
   assertThat(LearningService.directionEvidence(text,"금리",dictionary)).as(text).isNull();
  for(String text:List.of("금리는 2% 상승했다.","금리는 1% 하락했다.","금리는 2%로 동결했다."))
   assertThat(LearningService.directionEvidence(text,"금리",dictionary)).as(text).isNotNull();
 }
 @Test void disabledApplicationMasteryDoesNotAccessTheDatabase() {
  var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var service=new TermMasteryService(jdbc);
  for(String level:List.of("beginner","normal","analyst")) assertThat(service.applicationProbabilities(1L,level)).isEmpty();
  for(QuestionType type:List.of(QuestionType.DIRECTION,QuestionType.FACT_CHECK)) assertThat(service.answer(1L,2L,"금리","normal",true,3,type)).isFalse();
  org.mockito.Mockito.verifyNoInteractions(jdbc);
 }
}
