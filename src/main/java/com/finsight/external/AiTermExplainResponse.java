package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body returned by the AI service's POST /ai/terms/explain endpoint
 * (finsight-ai's {@code TermExplainResponse} is snake_case; its {@code plain_definition}
 * is intentionally not mapped here since the backend already has its own local
 * {@code Term.shortDefinition} for the base definition).
 */
public record AiTermExplainResponse(
        @JsonProperty("contextual_meaning") String contextExplanation,
        @JsonProperty("market_impact") String marketImpact
) {
}
