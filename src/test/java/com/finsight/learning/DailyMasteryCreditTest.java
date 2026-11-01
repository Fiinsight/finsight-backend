package com.finsight.learning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
class DailyMasteryCreditTest {
 @Test void wrongThenRetryCorrectOnlyChangesProbabilityForTheFirstAnswer() {
  var jdbc=mock(JdbcTemplate.class); var service=spy(new TermMasteryService(jdbc));
  ReflectionTestUtils.setField(service,"dailyCreditEnabled",true);
  when(jdbc.update(contains("INSERT INTO learning_mastery_credit"),any(Object[].class))).thenReturn(1,0);
  double[] probability={KnowledgeTracing.P_INIT};
  doAnswer(call->{probability[0]=KnowledgeTracing.update(probability[0],call.getArgument(3),"beginner");return null;}).when(service).answer(anyLong(),anyString(),anyString(),anyBoolean());
  assertThat(service.answer(1L,2L,"금리","beginner",false,3,QuestionType.DEFINITION)).isTrue();
  assertThat(service.answer(1L,2L,"금리","beginner",true,3,QuestionType.DEFINITION)).isFalse();
  assertThat(probability[0]).isEqualTo(KnowledgeTracing.update(.3,false,"beginner"));
  verify(service,times(1)).answer(1L,"금리","beginner",false);
 }
 @Test void unapprovedSchemaModeIsPracticeOnlyWithoutDatabaseAccess() {
  var jdbc=mock(JdbcTemplate.class);var service=new TermMasteryService(jdbc);
  assertThat(service.answer(1L,2L,"금리","beginner",true,3,QuestionType.DEFINITION)).isFalse();
  verifyNoInteractions(jdbc);
 }
}
