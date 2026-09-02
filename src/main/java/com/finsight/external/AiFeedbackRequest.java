package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body sent to the AI service's POST /ai/feedback/judgement endpoint
 * (finsight-ai's {@code FeedbackRequest} requires {@code news_id} and
 * {@code user_choice} in {@code UP|NEUTRAL|DOWN}).
 */
public record AiFeedbackRequest(
        @JsonProperty("news_id") Long newsId,
        @JsonProperty("user_choice") String userChoice,
        @JsonProperty("user_reason") String userReason,
        @JsonProperty("market_result") String marketResult
) {
}
