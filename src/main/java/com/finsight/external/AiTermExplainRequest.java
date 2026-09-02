package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body sent to the AI service's POST /ai/terms/explain endpoint. */
public record AiTermExplainRequest(String term, @JsonProperty("article_context") String context) {
}
