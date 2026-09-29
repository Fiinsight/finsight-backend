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
        @JsonProperty("detected_terms") List<String> detectedTerms
) {
}
