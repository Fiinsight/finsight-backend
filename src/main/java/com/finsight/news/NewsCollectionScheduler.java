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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the news collection pipeline every 3 hours: fetch candidates,
 * drop already-seen ones, rank by relevance, extract + assemble the top few,
 * and persist them. Each step is delegated to a focused collaborator in
 * {@code com.finsight.news.collect} so this class stays a thin orchestrator.
 */
@Component
public class NewsCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectionScheduler.class);

    private static final int TOP_CANDIDATE_COUNT = 10;
    private static final int TARGET_SAVED_COUNT = 3;
    private static final int MAX_EXTRACTION_ATTEMPTS = 10;

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

    @Scheduled(cron = "0 0 */3 * * *")
    public void collectNewsCandidates() {
        runOnce();
    }

    /**
     * Runs the pipeline once synchronously and reports how many articles made
     * it through each stage — used by both the cron trigger and the manual
     * "collect now" endpoint ({@link NewsCollectionController}).
     */
    public NewsCollectionResult runOnce() {
        log.info("News collection started at {}", Instant.now());

        List<NewsCandidate> fetched = rssFeedFetcher.fetchAll();
        List<NewsCandidate> unseen = newsDeduplicator.filterUnseen(fetched);
        List<NewsCandidate> topCandidates = newsRelevanceScorer.rank(unseen, TOP_CANDIDATE_COUNT);

        int saved = extractAndSave(topCandidates);

        log.info("News collection finished: fetched={}, unseen={}, topCandidates={}, saved={}",
                fetched.size(), unseen.size(), topCandidates.size(), saved);

        return new NewsCollectionResult(fetched.size(), unseen.size(), topCandidates.size(), saved);
    }

    private int extractAndSave(List<NewsCandidate> candidates) {
        int savedCount = 0;
        int attempts = 0;
        for (NewsCandidate candidate : candidates) {
            if (savedCount >= TARGET_SAVED_COUNT || attempts >= MAX_EXTRACTION_ATTEMPTS) {
                break;
            }
            attempts++;
            Optional<String> body = articleContentExtractor.extract(candidate.url());
            if (body.isEmpty() || body.get().isBlank()) {
                continue;
            }
            try {
                newsRepository.save(newsAssembler.assemble(candidate, body.get()));
                savedCount++;
            } catch (Exception e) {
                log.warn("Failed to save collected news for {}, skipping: {}", candidate.url(), e.getMessage());
            }
        }
        return savedCount;
    }
}
