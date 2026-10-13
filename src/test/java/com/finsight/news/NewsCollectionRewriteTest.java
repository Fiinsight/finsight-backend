package com.finsight.news;

import com.finsight.external.*;
import com.finsight.news.collect.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewsCollectionRewriteTest {
    @Test void everyNewArticleAllowsRewritingWithoutQuotaRetriesReducingEligibility() {
        var fetch=mock(RssFeedFetcher.class);var dedup=mock(NewsDeduplicator.class);var rank=mock(NewsRelevanceScorer.class);
        var extractor=mock(ArticleContentExtractor.class);var ai=mock(AiServiceClient.class);var repo=mock(NewsRepository.class);
        var assembler=new NewsAssembler(ai,new NewsCategoryClassifier(),new NewsSymbolMatcher(),new NewsSentimentClassifier(),extractor);
        var scheduler=new NewsCollectionScheduler(fetch,dedup,rank,extractor,assembler,repo);
        var candidates=java.util.stream.IntStream.range(0,15).mapToObj(i->new NewsCandidate("금리 변화 "+i,"https://fixture.test/"+i,"fixture",Instant.now(),1)).toList();
        when(fetch.fetchAll()).thenReturn(candidates);when(dedup.filterUnseen(candidates)).thenReturn(candidates);when(rank.rank(candidates,220)).thenReturn(candidates);
        when(extractor.extract(anyString())).thenReturn(Optional.of("금리가 변했다."));when(extractor.isUsable(anyString(),anyString())).thenReturn(true);
        var retry=new News("금리 변화","https://fixture.test/retry","fixture",Instant.now(),"금리가 변했다.");retry.setRewriteRetryPending(true);retry.setRewrittenBeginner("발췌");
        when(repo.findTop10ByRewriteRetryPendingTrueOrderByPublishedAtDesc()).thenReturn(List.of(retry));
        when(ai.rewrite(any())).thenReturn(Optional.of(new AiRewriteResponse("기사","일반",null,Map.of(),List.of(),"초보예요.","일반 사실.","분석 근거.","EXCERPT")));
        assertThat(scheduler.runOnce().saved()).isEqualTo(15);
        var requests=org.mockito.ArgumentCaptor.forClass(AiRewriteRequest.class);verify(ai,times(16)).rewrite(requests.capture());
        assertThat(requests.getAllValues()).allMatch(AiRewriteRequest::allowLlm);
        assertThat(retry.isRewriteRetryPending()).isFalse();verify(extractor,never()).extract(retry.getUrl());
    }
    @Test void simultaneousCollectionRunsRemainSequential() throws Exception {
        var fetch=mock(RssFeedFetcher.class);var active=new java.util.concurrent.atomic.AtomicInteger();var maximum=new java.util.concurrent.atomic.AtomicInteger();
        when(fetch.fetchAll()).thenAnswer(invocation->{int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);Thread.sleep(30);active.decrementAndGet();return List.of();});
        var scheduler=new NewsCollectionScheduler(fetch,mock(NewsDeduplicator.class),mock(NewsRelevanceScorer.class),mock(ArticleContentExtractor.class),mock(NewsAssembler.class),mock(NewsRepository.class));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(scheduler::runOnce);var second=pool.submit(scheduler::runOnce);first.get();second.get();
        }
        assertThat(maximum.get()).isEqualTo(1);
    }
}
