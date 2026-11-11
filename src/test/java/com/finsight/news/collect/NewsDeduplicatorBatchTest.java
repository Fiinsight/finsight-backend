package com.finsight.news.collect;

import com.finsight.news.NewsRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsDeduplicatorBatchTest {
    @Test void fiftyCandidatesUseOneDatabaseLookup() {
        var repository = mock(NewsRepository.class);
        var redis = mock(StringRedisTemplate.class);
        var candidates = candidates();
        assertThat(new NewsDeduplicator(repository, redis).filterUnseen(candidates)).containsExactlyElementsOf(candidates);
        assertThat(mockingDetails(repository).getInvocations()).hasSize(1);
    }

    @Test void fortyCandidatesSkippedByRankingRemainEligible() {
        var repository = mock(NewsRepository.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var marked = new java.util.HashSet<String>();
        when(redis.hasKey(anyString())).thenAnswer(call -> marked.contains(call.getArgument(0)));
        doAnswer(call -> { marked.add(call.getArgument(0)); return null; })
                .when(values).set(anyString(), eq("1"), eq(java.time.Duration.ofDays(7)));
        var service = new NewsDeduplicator(repository, redis);
        var first = service.filterUnseen(candidates());
        first.stream().limit(10).forEach(service::markCollected);
        assertThat(service.filterUnseen(candidates())).containsExactlyElementsOf(first.subList(10, 50));
    }

    private static List<NewsCandidate> candidates() {
        return IntStream.range(0, 50).mapToObj(i -> new NewsCandidate("영업이익 발표 " + i,
                "https://fixture.test/" + i, "검증 피드", Instant.EPOCH, 1)).toList();
    }
}
