package com.finsight.news;

import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.collect.NewsAssembler;
import com.finsight.news.collect.NewsCandidate;
import com.finsight.news.collect.NewsDeduplicator;
import com.finsight.news.collect.NewsRelevanceScorer;
import com.finsight.news.collect.RssFeedFetcher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/**
 * Orchestrates the news collection pipeline every 3 hours: fetch candidates,
 * drop already-seen ones, rank by relevance, extract + assemble the top few,
 * and persist them. Each step is delegated to a focused collaborator in
 * {@code com.finsight.news.collect} so this class stays a thin orchestrator.
 */
@Component
public class NewsCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectionScheduler.class);

    private static final int TOP_CANDIDATE_COUNT = 220;
    private static final int TARGET_SAVED_COUNT = 220;
    private static final int MAX_EXTRACTION_ATTEMPTS = 220;

    @Value("${finsight.news.collection.enabled:true}")
    private boolean collectionEnabled = true;
    @Value("${finsight.news.collection.on-startup:true}")
    private boolean collectionOnStartup = true;

    private final RssFeedFetcher rssFeedFetcher;
    private final NewsDeduplicator newsDeduplicator;
    private final NewsRelevanceScorer newsRelevanceScorer;
    private final ArticleContentExtractor articleContentExtractor;
    private final NewsAssembler newsAssembler;
    private final NewsRepository newsRepository;

    public NewsCollectionScheduler(RssFeedFetcher rssFeedFetcher,
                                    NewsDeduplicator newsDeduplicator,
                                    NewsRelevanceScorer newsRelevanceScorer,
                                    ArticleContentExtractor articleContentExtractor,
                                    NewsAssembler newsAssembler,
                                    NewsRepository newsRepository) {
        this.rssFeedFetcher = rssFeedFetcher;
        this.newsDeduplicator = newsDeduplicator;
        this.newsRelevanceScorer = newsRelevanceScorer;
        this.articleContentExtractor = articleContentExtractor;
        this.newsAssembler = newsAssembler;
        this.newsRepository = newsRepository;
    }

    private java.time.Clock clock = java.time.Clock.systemUTC();
    private Instant startedAt = Instant.now();
    private volatile Instant lastSuccess = Instant.EPOCH;
    private volatile Instant lastAttempt = Instant.EPOCH;
    private final java.util.concurrent.atomic.AtomicBoolean requested = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean collecting = new java.util.concurrent.atomic.AtomicBoolean();
    private java.util.concurrent.Executor executor = java.util.concurrent.ForkJoinPool.commonPool();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    public void requestCatchUp() {
        Instant now = clock.instant();
        if (!collectionEnabled || now.isBefore(startedAt.plusSeconds(45))
                || now.isBefore(lastSuccess.plusSeconds(10800)) || now.isBefore(lastAttempt.plusSeconds(600))
                || collecting.get() || !requested.compareAndSet(false,true)) return;
        lastAttempt = now;
        try {
            executor.execute(() -> { try { runOnce(40); }
                catch (Exception error) { log.warn("Requested collection failed: {}",error.getClass().getSimpleName()); }
                finally { requested.set(false); } });
        } catch (RuntimeException error) { requested.set(false); }
    }

    @Scheduled(cron = "0 0 */3 * * *", zone = "Asia/Seoul")
    public void collectNewsCandidates() {
        if (collectionEnabled) runOnce();
    }

    /**
     * Populate a fresh local/demo database shortly after startup instead of
     * making the first app session fall back to hard-coded sample headlines.
     * The work is asynchronous so the API becomes healthy immediately.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void collectNewsOnStartup() {
        startedAt = clock.instant();
        CompletableFuture.runAsync(() -> {
            if (redis != null) try {
                String previous = redis.opsForValue().get("finsight:news:last-success");
                if (previous != null) lastSuccess = Instant.parse(previous);
            } catch (RuntimeException ignored) { log.warn("Collection timestamp unavailable"); }
            if (collectionEnabled && collectionOnStartup) runOnce();
        })
                .exceptionally(error -> {
                    log.warn("Startup news collection failed: {}", error.getMessage());
                    return null;
                });
    }

    /**
     * Runs the pipeline once synchronously and reports how many articles made
     * it through each stage — used by both the cron trigger and the manual
     * "collect now" endpoint ({@link NewsCollectionController}).
     */
    public NewsCollectionResult runOnce() { return runOnce(TOP_CANDIDATE_COUNT); }

    private NewsCollectionResult runOnce(int limit) {
        if (!collecting.compareAndSet(false,true)) return new NewsCollectionResult(0,0,0,0);
        try {
        log.info("News collection started at {}", Instant.now());

        List<NewsCandidate> fetched = rssFeedFetcher.fetchAll();
        List<NewsCandidate> unseen = newsDeduplicator.filterUnseen(fetched);
        List<NewsCandidate> topCandidates = newsRelevanceScorer.rank(unseen, limit);

        for (News retry : limit < TOP_CANDIDATE_COUNT ? List.<News>of() : newsRepository.findTop10ByRewriteRetryPendingTrueOrderByPublishedAtDesc()) {
            try { newsAssembler.retryRewrite(retry); newsRepository.save(retry); }
            catch (Exception error) { log.warn("Rewrite retry deferred for newsId={}", retry.getId()); }
        }
        int saved = extractAndSave(topCandidates, limit);

        log.info("News collection finished: fetched={}, unseen={}, topCandidates={}, saved={}",
                fetched.size(), unseen.size(), topCandidates.size(), saved);

        if (!fetched.isEmpty() && (unseen.isEmpty() || saved > 0)) {
            lastSuccess = clock.instant();
            if (redis != null) try { redis.opsForValue().set("finsight:news:last-success",lastSuccess.toString()); }
            catch (RuntimeException ignored) { log.warn("Collection timestamp persistence unavailable"); }
        }
        log.info("News collection heapMiB={}", (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576);
        return new NewsCollectionResult(fetched.size(), unseen.size(), topCandidates.size(), saved);
        } finally { collecting.set(false); }
    }

    private int extractAndSave(List<NewsCandidate> candidates, int limit) {
        int savedCount = 0;
        int attempts = 0;
        for (NewsCandidate candidate : candidates) {
            if (savedCount >= Math.min(limit,TARGET_SAVED_COUNT) || attempts >= Math.min(limit,MAX_EXTRACTION_ATTEMPTS)) {
                break;
            }
            attempts++;
            var extracted = articleContentExtractor.extractArticle(candidate.url());
            Optional<String> body = extracted.map(ArticleContentExtractor.ExtractedArticle::body);
            if (body.isEmpty() || !articleContentExtractor.isUsable(candidate.title(), body.get())) {
                log.info("Skipping unsuitable article body for {}", candidate.url());
                continue;
            }
            try {
                newsRepository.save(newsAssembler.assemble(candidate.withImageUrl(candidate.imageUrl() != null ? candidate.imageUrl() : extracted.get().imageUrl()), body.get()));
                savedCount++;
            } catch (Exception e) {
                log.warn("Failed to save collected news for {}, skipping: {}", candidate.url(), e.getMessage());
            }
        }
        return savedCount;
    }

    public int reprocessExisting(int limit) {
        int updated = 0;
        for (News news : newsRepository.findTop100ByOrderByCreatedAtDesc()) {
            if (updated >= limit) break;
            newsAssembler.refreshMetadata(news);
            if (news.getRawContent() == null || news.getRawContent().isBlank()) continue;
            newsAssembler.reprocess(news);
            newsRepository.save(news);
            updated++;
        }
        return updated;
    }
}
