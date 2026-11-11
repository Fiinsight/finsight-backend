package com.finsight.news;

import com.finsight.news.collect.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class NewsCollectionRetryPipelineTest {
    @Test void rankingLimitLeavesFortyCandidatesAndReportsAllStages(CapturedOutput output) {
        var repository = mock(NewsRepository.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var marked = new HashSet<String>();
        when(redis.hasKey(anyString())).thenAnswer(call -> marked.contains(call.getArgument(0)));
        doAnswer(call -> { marked.add(call.getArgument(0)); return null; })
                .when(values).set(anyString(), eq("1"), eq(java.time.Duration.ofDays(7)));
        var dedup = new NewsDeduplicator(repository, redis);
        var fetched = IntStream.range(0, 50).mapToObj(i -> new NewsCandidate("수출 " + i,
                "https://fixture.test/pipeline/" + i, "검증 피드", Instant.EPOCH, 1)).toList();
        var fetcher = mock(RssFeedFetcher.class);
        when(fetcher.fetchAll()).thenReturn(fetched);
        var ranker = mock(NewsRelevanceScorer.class);
        when(ranker.rank(any(), eq(10))).thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().limit(10).toList());
        var extractor = mock(ArticleContentExtractor.class);
        when(extractor.extractArticle(anyString())).thenReturn(Optional.of(new ArticleContentExtractor.ExtractedArticle("로컬 검증 본문", null)));
        when(extractor.isUsable(anyString(), anyString())).thenReturn(true);
        var assembler = mock(NewsAssembler.class);
        when(assembler.assemble(any(), anyString())).thenAnswer(call -> {
            NewsCandidate candidate = call.getArgument(0);
            return new News(candidate.title(), candidate.url(), candidate.source(), candidate.publishedAt(), call.getArgument(1));
        });
        var scheduler = new NewsCollectionScheduler(fetcher, dedup, ranker, extractor, assembler, repository, mock(NewsImageVerifier.class));
        NewsCollectionResult first = ReflectionTestUtils.invokeMethod(scheduler, "runOnce", 10);
        assertThat(first.saved()).isEqualTo(10);
        assertThat(dedup.filterUnseen(fetched)).containsExactlyElementsOf(fetched.subList(10, 50));
        assertThat(output.getAll()).contains("source=검증 피드 fetched=50 unseen=50 ranked=10 attempts=10 extracted=10 usable=10 saved=10");

        // An extraction failure remains eligible on the following real pipeline pass.
        when(fetcher.fetchAll()).thenReturn(List.of(fetched.get(10)));
        when(extractor.extractArticle(fetched.get(10).url())).thenReturn(Optional.empty(),
                Optional.of(new ArticleContentExtractor.ExtractedArticle("로컬 검증 본문", null)));
        NewsCollectionResult failed = ReflectionTestUtils.invokeMethod(scheduler, "runOnce", 10);
        NewsCollectionResult retried = ReflectionTestUtils.invokeMethod(scheduler, "runOnce", 10);
        assertThat(failed.saved()).isZero();
        assertThat(retried.saved()).isEqualTo(1);
    }
}
