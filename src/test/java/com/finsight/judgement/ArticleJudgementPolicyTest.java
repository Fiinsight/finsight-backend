package com.finsight.judgement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class ArticleJudgementPolicyTest {
 @Test void secondTradingDayUsesKoreanCalendarAndKstBoundary() {
  var friday=Instant.parse("2026-10-30T01:00:00Z");
  assertThat(ArticleJudgementPolicy.reviewOnly(friday,Instant.parse("2026-11-02T14:59:59Z"))).isFalse();
  assertThat(ArticleJudgementPolicy.reviewOnly(friday,Instant.parse("2026-11-02T15:00:00Z"))).isTrue();
  assertThat(ArticleJudgementPolicy.reviewOnly(Instant.parse("2026-09-23T01:00:00Z"),Instant.parse("2026-09-28T01:00:00Z"))).isFalse();
  assertThat(ArticleJudgementPolicy.reviewOnly(null,Instant.now())).isTrue();
 }
 @Test void serverRejectsReviewOnlyBeforeSavingOrRevising() {
  var news=mock(com.finsight.news.NewsRepository.class);var rows=mock(JudgementRepository.class);
  var article=new com.finsight.news.News("삼성전자 실적 발표","https://fixture.test/old","fixture",Instant.parse("2026-01-02T00:00:00Z"),"삼성전자 실적");
  when(news.findById(1L)).thenReturn(java.util.Optional.of(article));
  assertThatThrownBy(()->new JudgementService(rows,news).recordJudgement(mock(com.finsight.auth.User.class),new JudgementRequest(1L,JudgementChoice.UP,null))).hasMessageContaining("복습용");
  verifyNoInteractions(rows);
 }
}
