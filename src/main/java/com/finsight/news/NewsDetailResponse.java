package com.finsight.news;

import com.finsight.briefing.SentimentHint;

public record NewsDetailResponse(
        Long id,
        String title,
        String url,
        String source,
        String rawContent,
        String rewrittenBeginner,
        String rewrittenNormal,
        String rewrittenAnalyst,
        String importanceReason,
        String relatedSymbol,
        SentimentHint sentimentHint,
        String category
) {
    public static NewsDetailResponse from(News news) {
        return new NewsDetailResponse(
                news.getId(),
                news.getTitle(),
                news.getUrl(),
                news.getSource(),
                news.getRawContent(),
                news.getRewrittenBeginner(),
                news.getRewrittenNormal(),
                news.getRewrittenAnalyst(),
                news.getImportanceReason(),
                news.getRelatedSymbol(),
                news.getSentimentHint(),
                news.getCategory()
        );
    }
}
