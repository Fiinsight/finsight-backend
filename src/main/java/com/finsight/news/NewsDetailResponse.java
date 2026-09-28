package com.finsight.news;

import com.finsight.briefing.SentimentHint;
import com.finsight.news.collect.NewsCategoryClassifier;
import java.time.Instant;
import java.util.List;

public record NewsDetailResponse(
        Long id,
        String title,
        String url,
        String source,
        Instant publishedAt,
        String rawContent,
        String rewrittenBeginner,
        String rewrittenNormal,
        String rewrittenAnalyst,
        String importanceReason,
        String relatedSymbol,
        SentimentHint sentimentHint,
        String category,
        List<String> keyTerms
) {
    public static NewsDetailResponse from(News news, NewsCategoryClassifier categoryClassifier) {
        return new NewsDetailResponse(
                news.getId(),
                news.getTitle(),
                news.getUrl(),
                news.getSource(),
                news.getPublishedAt(),
                news.getRawContent(),
                news.getRewrittenBeginner(),
                news.getRewrittenNormal(),
                news.getRewrittenAnalyst(),
                news.getImportanceReason(),
                news.getRelatedSymbol(),
                news.getSentimentHint(),
                categoryClassifier.classify(news.getTitle()),
                news.getKeyTerms()
        );
    }
}
