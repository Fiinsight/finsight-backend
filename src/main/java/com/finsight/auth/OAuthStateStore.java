package com.finsight.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OAuthStateStore {
    private final StringRedisTemplate redis;
    public OAuthStateStore(StringRedisTemplate redis) { this.redis = redis; }

    public void put(String state, String destination) {
        try {
            redis.opsForValue().set("finsight:oauth:state:" + state, destination, Duration.ofMinutes(5));
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "로그인 연결을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public String consume(String state) {
        if (!state.matches("[0-9a-fA-F-]{36}")) return null;
        try {
            // GETDEL prevents two instances from accepting the same callback.
            return redis.opsForValue().getAndDelete("finsight:oauth:state:" + state);
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "로그인 연결을 확인하지 못했습니다. 다시 시도해 주세요.");
        }
    }
}
