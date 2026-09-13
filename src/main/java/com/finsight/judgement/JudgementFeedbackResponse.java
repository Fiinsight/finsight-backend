package com.finsight.judgement;

public record JudgementFeedbackResponse(
        Long newsId,
        JudgementChoice choice,
        String feedback
) {
}

