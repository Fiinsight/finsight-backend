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
        int relevanceScore,
        String imageUrl
) {
    public NewsBriefResponse(Long id, String title, String summary, String importanceReason, String relatedSymbol,
            String category, SentimentHint sentimentHint, String url, String source, java.time.Instant publishedAt, int relevanceScore) {
        this(id,title,summary,importanceReason,relatedSymbol,category,sentimentHint,url,source,publishedAt,relevanceScore,null);
    }
}
