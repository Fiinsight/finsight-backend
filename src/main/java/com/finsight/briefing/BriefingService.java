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
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

@Service
public class BriefingService {

    private static final int MIN_REQUIRED_ITEMS = 3;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

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
        Instant today = LocalDate.now(KOREA_ZONE).atStartOfDay(KOREA_ZONE).toInstant();
        // ponytail: rank the latest 1000 candidates; use DB scoring if the archive grows beyond this window.
        return newsRepository.findAllByOrderByPublishedAtDesc(PageRequest.of(0, 1000)).stream()
                .filter(this::isUsable)
                .sorted(Comparator.comparing((News n) -> n.getPublishedAt() != null && !n.getPublishedAt().isBefore(today)).reversed()
                        .thenComparing(Comparator.comparingInt((News n) -> relevanceScorer.scoreTitle(n.getTitle())).reversed())
                        .thenComparing(News::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(News::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private boolean isUsable(News news) {
        return articleContentExtractor.isUsable(news.getTitle(), news.getRawContent());
    }

    private NewsBriefResponse toBriefResponse(News news) {
        String summary = news.getRewrittenNormal() != null && !news.getRewrittenNormal().isBlank()
                ? news.getRewrittenNormal() : "요약을 확인할 수 없음 · 원문 링크에서 확인하세요 (fallback)";
        return new NewsBriefResponse(
                news.getId(),
                news.getTitle(),
                NewsDetailResponse.excerpt(summary),
                newsCategoryClassifier.importanceReason(news.getTitle(), news.getRawContent(), news.getImportanceReason(),
                        news.getRelatedSymbol()),
                news.getRelatedSymbol(),
                newsCategoryClassifier.classify(news.getTitle()),
                news.getSentimentHint() != null ? news.getSentimentHint() : SentimentHint.NEUTRAL,
                news.getUrl(), news.getSource(), news.getPublishedAt(), relevanceScorer.scoreTitle(news.getTitle())
        );
    }

}
