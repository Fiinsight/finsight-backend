package com.finsight.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class OAuthStateStoreTest {
    @Test void stateIsSharedExpiringAndConsumedAtomically() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String nonce = java.util.UUID.randomUUID().toString(), key = "finsight:oauth:state:" + nonce;
        var first = new OAuthStateStore(redis);
        var second = new OAuthStateStore(redis);
        first.put(nonce, "finsight://auth/kakao");
        verify(values).set(key, "finsight://auth/kakao", Duration.ofMinutes(5));
        when(values.getAndDelete(key)).thenReturn("finsight://auth/kakao", (String)null);
        assertThat(second.consume(nonce)).isEqualTo("finsight://auth/kakao");
        assertThat(first.consume(nonce)).isNull();
        assertThat(first.consume("oversized-invalid-state")).isNull();
    }
    @Test void outageDoesNotFallBackToAnUnsharedState() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("secret"));
        assertThatThrownBy(() -> new OAuthStateStore(redis).put("nonce", "destination"))
                .hasMessageContaining("503").hasMessageNotContaining("secret");
    }
}
