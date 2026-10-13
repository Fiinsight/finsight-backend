package com.finsight.judgement;

public record JudgementFeedbackResponse(
        Long newsId, JudgementChoice choice, String feedback,
        Long judgementId, java.time.LocalDate expectedResultDate, java.time.Instant expectedResultAt
) {
    public JudgementFeedbackResponse(Long newsId, JudgementChoice choice, String feedback) {
        this(newsId,choice,feedback,null,null,null);
    }
}
