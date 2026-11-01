package com.finsight.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);
    private static final long MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;

    public AuthRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void check(String action, String email, String ipAddress) {
        checkKey("finsight:auth:attempts:" + action + ":ip:" + sha256(ipAddress), 30);
        if (email != null && !email.isBlank())
            checkKey("finsight:auth:attempts:" + action + ":account:" + sha256(email.trim().toLowerCase(java.util.Locale.ROOT)), MAX_ATTEMPTS);
    }

    private void checkKey(String key, long limit) {
        Long attempts;
        try {
            attempts = redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class),
                    java.util.List.of(key), Long.toString(WINDOW.toSeconds()));
            if (attempts == null) throw new IllegalStateException("Missing counter");
        } catch (RuntimeException unavailable) {
            log.warn("Auth rate limiter unavailable: {}", unavailable.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "로그인 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (attempts > limit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash auth rate-limit key", exception);
        }
    }
}
