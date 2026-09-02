package com.finsight.judgement;

import java.time.Instant;

public record JudgementHistoryResponse(
        Long judgementId,
        Long newsId,
        String newsTitle,
        JudgementChoice choice,
        String reasonText,
        Instant createdAt,
        String actualDirection,
        Double actualChangePercent,
        String feedbackText,
        Instant feedbackGeneratedAt
) {
}
