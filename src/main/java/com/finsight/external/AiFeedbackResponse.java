package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response body returned by the AI service's POST /ai/feedback/judgement endpoint. */
public record AiFeedbackResponse(@JsonProperty("feedback") String feedbackText) {
}
