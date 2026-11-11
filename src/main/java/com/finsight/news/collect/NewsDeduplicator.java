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
        if (candidates.isEmpty()) return List.of();
        java.util.Set<String> stored = java.util.Set.of();
        try {
            stored = new java.util.HashSet<>(newsRepository.findUrlsIn(
                    candidates.stream().map(NewsCandidate::url).distinct().toList()));
        } catch (RuntimeException error) {
            log.warn("Batch news dedup lookup unavailable; keeping unmarked candidates");
        }
        List<NewsCandidate> result = new ArrayList<>();
        for (NewsCandidate candidate : candidates) {
            if (!stored.contains(candidate.url()) && isUnseen(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private boolean isUnseen(NewsCandidate candidate) {
        try {
            String redisKey = REDIS_SEEN_KEY_PREFIX + sha256(candidate.url());
            // Ranking/extraction may skip this candidate; only successful saves mark it seen.
            return !Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
        } catch (Exception e) {
            log.warn("Dedup check failed for {}, keeping candidate as a precaution: {}", candidate.url(), e.getMessage());
            return true;
        }
    }

    public void markCollected(NewsCandidate candidate) {
        try {
            redisTemplate.opsForValue().set(REDIS_SEEN_KEY_PREFIX + sha256(candidate.url()), "1", SEEN_TTL);
        } catch (RuntimeException error) {
            log.warn("Collected-news marker unavailable for source={}; database URL dedup remains active", candidate.source());
        }
    }

    public static List<com.finsight.news.News> distinctArticles(List<com.finsight.news.News> ranked) {
        var result = new ArrayList<com.finsight.news.News>();
        var features = new java.util.IdentityHashMap<com.finsight.news.News, TitleFeatures>();
        for (var item : ranked) {
            String title=normalizedTitle(item.getTitle());
            features.put(item,new TitleFeatures(title,titleGrams(title)));
            if (result.stream().noneMatch(previous -> sameEvent(previous, item, features))) result.add(item);
        }
        return List.copyOf(result);
    }

    private record TitleFeatures(String normalized, java.util.Set<String> grams) {}
    private static boolean sameEvent(com.finsight.news.News left, com.finsight.news.News right,
            java.util.Map<com.finsight.news.News,TitleFeatures> features) {
        if (left.getUrl() != null && left.getUrl().equals(right.getUrl())) return true;
        if (left.getPublishedAt() == null || right.getPublishedAt() == null
                || Math.abs(java.time.Duration.between(left.getPublishedAt(), right.getPublishedAt()).toHours()) > 24) return false;
        String a = features.get(left).normalized(), b = features.get(right).normalized();
        if (a.equals(b)) return true;
        var first = features.get(left).grams(); var second = features.get(right).grams();
        var shared = new java.util.HashSet<>(first); shared.retainAll(second);
        var union = new java.util.HashSet<>(first); union.addAll(second);
        return shared.size() >= 8 && !union.isEmpty() && (double) shared.size() / union.size() >= 0.6;
    }

    private static String normalizedTitle(String title) {
        return title == null ? "" : title.replaceAll("\\[(속보|종합\\d*)\\]|\\(종합\\d*\\)", "").replaceAll("[^0-9A-Za-z가-힣]", "").toLowerCase(java.util.Locale.ROOT);
    }
    private static java.util.Set<String> titleGrams(String title) {
        var grams = new java.util.HashSet<String>();
        for (int i = 0; i + 2 <= title.length(); i++) grams.add(title.substring(i, i + 2));
        return grams;
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
