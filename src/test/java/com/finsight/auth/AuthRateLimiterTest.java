package com.finsight.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

class AuthRateLimiterTest {

    @Test
    void rejectsTheSixthAttemptInTheSameWindow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(6L);

        AuthRateLimiter limiter = new AuthRateLimiter(redis);

        assertThatThrownBy(() -> limiter.check("login", "person@example.com", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("요청이 너무 많습니다");
    }
}
