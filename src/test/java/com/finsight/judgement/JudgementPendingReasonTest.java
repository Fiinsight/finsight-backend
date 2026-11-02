package com.finsight.judgement;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class JudgementPendingReasonTest {
 private Judgement pending(String title,String date) {
  var news=mock(com.finsight.news.News.class);when(news.getTitle()).thenReturn(title);
  var row=mock(Judgement.class);when(row.getNews()).thenReturn(news);when(row.getCreatedAt()).thenReturn(Instant.parse(date));
  return row;
 }
 @Test void weekendJudgementIsDueOnMondayAt1540Kst() {
  var row=pending("삼성전자","2026-11-07T03:00:00Z");
  assertThat(JudgementPendingReason.comparisonDate(row.getCreatedAt())).isEqualTo(LocalDate.parse("2026-11-09"));
  assertThat(JudgementPendingReason.of(row,Instant.parse("2026-11-09T06:39:59Z"))).isEqualTo(JudgementPendingReason.NOT_DUE);
  assertThat(JudgementPendingReason.of(row,Instant.parse("2026-11-09T06:40:00Z"))).isEqualTo(JudgementPendingReason.PRICE_UNAVAILABLE);
 }
 @Test void qaUnsupportedAndCompletedHaveDistinctStatuses() {
  var qa=pending("로컬 QA 삼성전자","2026-11-07T03:00:00Z");
  assertThat(JudgementPendingReason.of(qa,Instant.now())).isEqualTo(JudgementPendingReason.EXCLUDED_QA);
  var unsupported=pending("삼성전자","2028-01-07T03:00:00Z");
  assertThat(JudgementPendingReason.of(unsupported,Instant.now())).isEqualTo(JudgementPendingReason.CALENDAR_UNSUPPORTED);
  assertThat(JudgementPendingReason.comparisonDate(unsupported.getCreatedAt())).isNull();
  when(qa.getActualDirection()).thenReturn("UP");assertThat(JudgementPendingReason.of(qa,Instant.now())).isNull();
 }
 @Test void historyResponseCarriesPendingReasonAndExpectedDate() {
  var row=pending("삼성전자","2026-11-07T03:00:00Z");
  var user=mock(com.finsight.auth.User.class);var repo=mock(JudgementRepository.class);
  when(repo.findByIdAndUser(1L,user)).thenReturn(Optional.of(row));
  var service=new JudgementService(repo,mock(com.finsight.news.NewsRepository.class));
  org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"),ZoneId.of("Asia/Seoul")));
  var response=service.getDetail(user,1L);
  assertThat(response.pendingReason()).isEqualTo(JudgementPendingReason.NOT_DUE);
  assertThat(response.expectedResultAt()).isEqualTo(Instant.parse("2026-11-09T06:40:00Z"));
 }
}
