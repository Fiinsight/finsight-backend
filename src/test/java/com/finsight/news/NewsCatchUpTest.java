package com.finsight.news;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.finsight.news.collect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
class NewsCatchUpTest {
 @Test void staleRequestsAreBoundedCoalescedCooledDownAndAvoidStartup() {
  var fetch=mock(RssFeedFetcher.class); var rank=mock(NewsRelevanceScorer.class);
  var scheduler=new NewsCollectionScheduler(fetch,mock(NewsDeduplicator.class),rank,mock(ArticleContentExtractor.class),mock(NewsAssembler.class),mock(NewsRepository.class));
  var now=Instant.parse("2026-11-01T00:00:00Z"); var jobs=new ArrayList<Runnable>();
  ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(now,ZoneOffset.UTC));
  ReflectionTestUtils.setField(scheduler,"startedAt",now);
  ReflectionTestUtils.setField(scheduler,"executor",(java.util.concurrent.Executor)jobs::add);
  scheduler.requestCatchUp(); assertThat(jobs).isEmpty();
  ReflectionTestUtils.setField(scheduler,"startedAt",now.minusSeconds(60));
  for(int i=0;i<20;i++) scheduler.requestCatchUp();
  assertThat(jobs).hasSize(1); jobs.removeFirst().run();
  verify(rank).rank(any(),eq(40));
  scheduler.requestCatchUp(); assertThat(jobs).isEmpty();
  ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(now.plusSeconds(601),ZoneOffset.UTC));
  scheduler.requestCatchUp(); assertThat(jobs).hasSize(1);
 }
 @Test void extractionFailureDoesNotAdvanceSuccessfulCollectionTime() {
  var fetch=mock(RssFeedFetcher.class); var dedup=mock(NewsDeduplicator.class); var rank=mock(NewsRelevanceScorer.class); var extract=mock(ArticleContentExtractor.class);
  var candidate=new NewsCandidate("수출 증가","https://fixture.test/news","fixture",Instant.now(),1);
  when(fetch.fetchAll()).thenReturn(List.of(candidate)); when(dedup.filterUnseen(any())).thenReturn(List.of(candidate));
  when(rank.rank(any(),eq(220))).thenReturn(List.of(candidate)); when(extract.extract(any())).thenReturn(Optional.empty());
  var scheduler=new NewsCollectionScheduler(fetch,dedup,rank,extract,mock(NewsAssembler.class),mock(NewsRepository.class));
  scheduler.runOnce(); assertThat(ReflectionTestUtils.getField(scheduler,"lastSuccess")).isEqualTo(Instant.EPOCH);
 }
 @Test void recentSuccessDoesNotCollect() {
  var scheduler=new NewsCollectionScheduler(null,null,null,null,null,null);
  var now=Instant.now(); ReflectionTestUtils.setField(scheduler,"startedAt",now.minusSeconds(60));
  ReflectionTestUtils.setField(scheduler,"lastSuccess",now.minusSeconds(3600));
  scheduler.requestCatchUp();
  assertThat(((java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(scheduler,"requested")).get()).isFalse();
 }
}
