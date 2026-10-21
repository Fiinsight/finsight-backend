package com.finsight.briefing;

import com.finsight.news.News;
import com.finsight.news.NewsDetailResponse;
import com.finsight.news.NewsRepository;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.collect.NewsCategoryClassifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import com.finsight.news.collect.NewsRelevanceScorer;
import com.finsight.news.collect.NewsDeduplicator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

@Service
public class BriefingService {

    private static final int MIN_REQUIRED_ITEMS = 3;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final com.github.benmanes.caffeine.cache.Cache<String, List<News>> rankingCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(1)
                    .expireAfterWrite(java.time.Duration.ofSeconds(60)).build();
    private final NewsRelevanceScorer relevanceScorer;
    private final NewsRepository newsRepository;
    private final ArticleContentExtractor articleContentExtractor;
    private final NewsCategoryClassifier newsCategoryClassifier;

    public BriefingService(NewsRepository newsRepository, ArticleContentExtractor articleContentExtractor,
                           NewsCategoryClassifier newsCategoryClassifier, NewsRelevanceScorer relevanceScorer) {
        this.relevanceScorer = relevanceScorer;
        this.newsRepository = newsRepository;
        this.articleContentExtractor = articleContentExtractor;
        this.newsCategoryClassifier = newsCategoryClassifier;
    }

    public List<NewsBriefResponse> getTodayBriefing() {
        return rankedNews().stream().limit(MIN_REQUIRED_ITEMS).map(this::toBriefResponse).toList();
    }

    public List<NewsBriefResponse> getMoreBriefing(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        long offset = MIN_REQUIRED_ITEMS + (long) page * size;
        return rankedNews().stream().skip(offset).limit(size).map(this::toBriefResponse).toList();
    }

    private List<News> rankedNews() {
        return rankingCache.get(LocalDate.now(KOREA_ZONE).toString(), ignored -> loadRankedNews());
    }

    private List<News> loadRankedNews() {
        Instant today = LocalDate.now(KOREA_ZONE).atStartOfDay(KOREA_ZONE).toInstant();
        // ponytail: rank the latest 1000 candidates; use DB scoring if the archive grows beyond this window.
        List<News> ranked = NewsDeduplicator.distinctArticles(newsRepository.findAllByOrderByPublishedAtDesc(PageRequest.of(0, 1000)).stream()
                .filter(this::isUsable)
                .filter(news -> relevanceScorer.scoreArticle(news.getTitle(), news.getRawContent()) > 0)
                .sorted(Comparator.comparing((News n) -> n.getPublishedAt() != null && !n.getPublishedAt().isBefore(today)).reversed()
                        .thenComparing(Comparator.comparingInt((News n) -> relevanceScorer.scoreArticle(n.getTitle(), n.getRawContent())).reversed())
                        .thenComparing(News::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(News::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList());
        java.util.Set<String> categories = new java.util.HashSet<>();
        java.util.List<News> selected = new java.util.ArrayList<>();
        boolean hasToday = ranked.stream().anyMatch(n -> !n.getPublishedAt().isBefore(today));
        for (News news : ranked) {
            if (hasToday && news.getPublishedAt().isBefore(today)) continue;
            if (categories.add(newsCategoryClassifier.classify(news.getTitle(), news.getRawContent()))) selected.add(news);
            if (selected.size() == MIN_REQUIRED_ITEMS) break;
        }
        for (News news : ranked) if (selected.size() < MIN_REQUIRED_ITEMS && !selected.contains(news)) selected.add(news);
        for (News news : ranked) if (!selected.contains(news)) selected.add(news);
        return List.copyOf(selected);
    }

    private boolean isUsable(News news) {
        return (news.getSource() == null || !news.getSource().startsWith("SYNTHETIC"))
                && news.getUrl() != null && !news.getUrl().contains("example.invalid")
                && articleContentExtractor.isUsable(news.getTitle(), news.getRawContent());
    }

    public NewsBriefResponse toBriefResponse(News news) {
        String summary = news.getRewrittenNormal() != null && !news.getRewrittenNormal().isBlank()
                ? news.getRewrittenNormal() : "요약을 확인할 수 없습니다. 원문 링크에서 확인하세요.";
        summary = ArticleContentExtractor.stripPhotoNoise(summary);
        if (summary.isBlank()) summary = articleContentExtractor.clean(news.getTitle(), news.getRawContent());
        return new NewsBriefResponse(
                news.getId(),
                news.getTitle(),
                NewsDetailResponse.excerpt(summary),
                newsCategoryClassifier.importanceReason(news.getTitle(), news.getRawContent(), news.getImportanceReason(),
                        news.getRelatedSymbol()),
                news.getRelatedSymbol(),
                newsCategoryClassifier.classify(news.getTitle(), news.getRawContent()),
                news.getSentimentHint() != null ? news.getSentimentHint() : SentimentHint.NEUTRAL,
                news.getUrl(), news.getSource(), news.getPublishedAt(), relevanceScorer.scoreArticle(news.getTitle(), news.getRawContent())
        );
    }

}
