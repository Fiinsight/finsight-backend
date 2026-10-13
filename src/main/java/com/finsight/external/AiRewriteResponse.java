package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Response body returned by the AI service's POST /ai/news/rewrite endpoint
 * for a single reading level (finsight-ai's {@code RewriteResponse} is snake_case).
 */
public record AiRewriteResponse(
        String title,
        String summary,
        @JsonProperty("importance_reason") String importanceReason,
        @JsonProperty("importance_reasons") Map<String, String> importanceReasons,
        @JsonProperty("detected_terms") List<String> detectedTerms,
        String beginner, String normal, String analyst, String mode, boolean retryable
) {
    public AiRewriteResponse(String title, String summary, String reason, Map<String,String> reasons, List<String> terms, String beginner, String normal, String analyst, String mode) {
        this(title, summary, reason, reasons, terms, beginner, normal, analyst, mode, false);
    }
    public AiRewriteResponse(String title, String summary, String reason, Map<String,String> reasons, List<String> terms) {
        this(title, summary, reason, reasons, terms, null, null, null, "EXCERPT", false);
    }
}
