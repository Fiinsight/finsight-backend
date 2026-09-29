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
        String key = "finsight:auth:attempts:" + action + ":" + ipAddress + ":" + sha256(email.trim().toLowerCase());
        try {
            Long attempts = redis.opsForValue().increment(key);
            if (attempts != null && attempts == 1L) {
                redis.expire(key, WINDOW);
            }
            if (attempts != null && attempts > MAX_ATTEMPTS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Redis is a safety layer; a Redis outage must not turn the demo into an auth outage.
            log.warn("Auth rate limiter unavailable; allowing request: {}", exception.getMessage());
        }
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
