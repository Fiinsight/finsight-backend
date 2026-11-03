package com.finsight.news.collect;

import com.finsight.news.NewsRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsDeduplicatorRetryTest {
    @Test void rankingOrExtractionFailuresDoNotSuppressTheCandidateForSevenDays() {
        var repository = mock(NewsRepository.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var seen = new java.util.HashSet<String>();
        when(redis.hasKey(anyString())).thenAnswer(call -> seen.contains(call.getArgument(0)));
        doAnswer(call -> { seen.add(call.getArgument(0)); return null; }).when(values).set(anyString(), eq("1"), eq(java.time.Duration.ofDays(7)));
        var service = new NewsDeduplicator(repository, redis);
        var candidate = new NewsCandidate("수출 증가", "https://fixture.test/news", "한국경제", Instant.EPOCH, 1);
        assertThat(service.filterUnseen(List.of(candidate))).containsExactly(candidate);
        assertThat(service.filterUnseen(List.of(candidate))).containsExactly(candidate);
        verifyNoInteractions(values);
        service.markCollected(candidate);
        assertThat(service.filterUnseen(List.of(candidate))).isEmpty();
        when(repository.existsByUrl(candidate.url())).thenReturn(true);
        seen.clear();
        assertThat(service.filterUnseen(List.of(candidate))).isEmpty();
    }
}
