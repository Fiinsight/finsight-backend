package com.finsight.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

class AuthRateLimiterTest {
    @Test void rejectsSixthAccountAttemptEvenAcrossDifferentIps() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L, 6L);
        assertThatThrownBy(() -> new AuthRateLimiter(redis).check("login", "person@example.com", "127.0.0.2"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
    }
    @Test void ipLimitCoversRotatingAccountsAndKakao() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(31L);
        assertThatThrownBy(() -> new AuthRateLimiter(redis).check("kakao", "", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
    }
    @Test void redisFailureDoesNotDisableProtection() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("secret"));
        assertThatThrownBy(() -> new AuthRateLimiter(redis).check("login", "person@example.com", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503").hasMessageNotContaining("secret");
    }
}
