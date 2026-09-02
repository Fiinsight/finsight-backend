package com.finsight.news.collect;

import com.finsight.news.NewsRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Filters out candidates that were already collected: either already
 * persisted (checked against {@link NewsRepository}), or seen recently
 * (tracked via a Redis SETNX marker with a multi-day TTL).
 */
@Component
public class NewsDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(NewsDeduplicator.class);

    private static final String REDIS_SEEN_KEY_PREFIX = "finsight:news:seen:";
    private static final Duration SEEN_TTL = Duration.ofDays(7);

    private final NewsRepository newsRepository;
    private final StringRedisTemplate redisTemplate;

    public NewsDeduplicator(NewsRepository newsRepository, StringRedisTemplate redisTemplate) {
        this.newsRepository = newsRepository;
        this.redisTemplate = redisTemplate;
    }

    public List<NewsCandidate> filterUnseen(List<NewsCandidate> candidates) {
        List<NewsCandidate> result = new ArrayList<>();
        for (NewsCandidate candidate : candidates) {
            if (isUnseen(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private boolean isUnseen(NewsCandidate candidate) {
        try {
            if (newsRepository.existsByUrl(candidate.url())) {
                return false;
            }
            String redisKey = REDIS_SEEN_KEY_PREFIX + sha256(candidate.url());
            Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", SEEN_TTL);
            // firstSeen == null means Redis was unreachable; keep the candidate
            // rather than silently dropping it just because dedup is unavailable.
            return firstSeen == null || firstSeen;
        } catch (Exception e) {
            log.warn("Dedup check failed for {}, keeping candidate as a precaution: {}", candidate.url(), e.getMessage());
            return true;
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always available on the JVM; fall back to the raw
            // string as a key rather than throwing.
            return input;
        }
    }
}
