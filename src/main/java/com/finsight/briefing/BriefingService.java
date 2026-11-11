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

    private record RankedNews(News news, int score, String category) {}
    private static final int MIN_REQUIRED_ITEMS = 3;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final com.github.benmanes.caffeine.cache.Cache<String, List<RankedNews>> rankingCache =
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

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.finsight.news.NewsCollectionScheduler collection;

    public List<NewsBriefResponse> getTodayBriefing() {
        if (collection != null) collection.requestCatchUp();
        return getTodayBriefingWithoutCatchUp();
    }

    public List<NewsBriefResponse> getTodayBriefingWithoutCatchUp() {
        return rankedNews().stream().limit(MIN_REQUIRED_ITEMS).map(row -> toBriefResponse(row.news(),row.score(),row.category())).toList();
    }

    public List<NewsBriefResponse> getMoreBriefing(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        long offset = MIN_REQUIRED_ITEMS + (long) page * size;
        return rankedNews().stream().skip(offset).limit(size).map(row -> toBriefResponse(row.news(),row.score(),row.category())).toList();
    }

    private List<RankedNews> rankedNews() {
        return rankingCache.get(LocalDate.now(KOREA_ZONE).toString(), ignored -> loadRankedNews());
    }

    private List<RankedNews> loadRankedNews() {
        Instant today = LocalDate.now(KOREA_ZONE).atStartOfDay(KOREA_ZONE).toInstant();
        Instant now = Instant.now();
        List<RankedNews> scored = newsRepository.findByPublishedAtBetweenOrderByPublishedAtDesc(
                    today.minus(java.time.Duration.ofDays(7)), now, PageRequest.of(0,300)).stream()
                .filter(n -> n.getPublishedAt() != null && !n.getPublishedAt().isAfter(now))
                .filter(this::isUsable)
                .map(n -> new RankedNews(n,relevanceScorer.scoreArticle(n.getTitle(),n.getRawContent()),newsCategoryClassifier.classify(n.getTitle(),n.getRawContent())))
                .filter(row -> row.score() > 0)
                .sorted(Comparator.comparing((RankedNews row) -> !row.news().getPublishedAt().isBefore(today)).reversed()
                        .thenComparing(Comparator.comparingInt(RankedNews::score).reversed())
                        .thenComparing(row -> row.news().getPublishedAt(), Comparator.reverseOrder())
                        .thenComparing(row -> row.news().getId(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        var byArticle = scored.stream().collect(java.util.stream.Collectors.toMap(RankedNews::news,row->row));
        List<News> ranked = NewsDeduplicator.distinctArticles(scored.stream().map(RankedNews::news).toList());
        java.util.Set<String> categories = new java.util.HashSet<>();
        java.util.List<News> selected = new java.util.ArrayList<>();
        boolean hasToday = ranked.stream().anyMatch(n -> !n.getPublishedAt().isBefore(today));
        for (News news : ranked) {
            if (hasToday && news.getPublishedAt().isBefore(today)) continue;
            if (categories.add(byArticle.get(news).category())) selected.add(news);
            if (selected.size() == MIN_REQUIRED_ITEMS) break;
        }
        for (News news : ranked) if (selected.size() < MIN_REQUIRED_ITEMS && !selected.contains(news)) selected.add(news);
        for (News news : ranked) if (!selected.contains(news)) selected.add(news);
        return selected.stream().map(byArticle::get).toList();
    }

    private boolean isUsable(News news) {
        return (news.getSource() == null || !news.getSource().startsWith("SYNTHETIC"))
                && news.getUrl() != null && !news.getUrl().contains("example.invalid")
                && articleContentExtractor.isUsable(news.getTitle(), news.getRawContent());
    }

    public NewsBriefResponse toBriefResponse(News news) {
        return toBriefResponse(news,relevanceScorer.scoreArticle(news.getTitle(),news.getRawContent()),newsCategoryClassifier.classify(news.getTitle(),news.getRawContent()));
    }
    private NewsBriefResponse toBriefResponse(News news,int score,String category) {
        String summary = news.getRewrittenNormal() != null && !news.getRewrittenNormal().isBlank()
                ? news.getRewrittenNormal() : "요약을 확인할 수 없습니다. 원문 링크에서 확인하세요.";
        summary = ArticleContentExtractor.stripPhotoNoise(summary);
        if (summary.isBlank()) summary = articleContentExtractor.clean(news.getTitle(), news.getRawContent());
        return new NewsBriefResponse(
                news.getId(),
                NewsDetailResponse.cardText(news.getTitle()),
                NewsDetailResponse.cardSummary(summary),
                NewsDetailResponse.cardPoint(newsCategoryClassifier.importanceReason(news.getTitle(), news.getRawContent(), news.getImportanceReason(),
                        news.getRelatedSymbol())),
                news.getRelatedSymbol(),
                category,
                news.getSentimentHint() != null ? news.getSentimentHint() : SentimentHint.NEUTRAL,
                news.getUrl(), news.getSource(), news.getPublishedAt(), score, news.getImageUrl()
        );
    }

}
