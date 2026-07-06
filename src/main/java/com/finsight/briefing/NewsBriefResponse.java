package com.finsight.briefing;

public record NewsBriefResponse(
        Long id,
        String title,
        String summary,
        String importanceReason,
        String relatedSymbol,
        SentimentHint sentimentHint
) {
}

