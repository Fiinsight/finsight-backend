package com.finsight.briefing;

public record NewsBriefResponse(
        Long id,
        String title,
        String summary,
        String importanceReason,
        String relatedSymbol,
        String category,
        SentimentHint sentimentHint,
        String url,
        String source,
        java.time.Instant publishedAt,
        int relevanceScore
) {
}
