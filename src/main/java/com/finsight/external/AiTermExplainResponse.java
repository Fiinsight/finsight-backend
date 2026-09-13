package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body returned by the AI service's POST /ai/terms/explain endpoint
 * (finsight-ai's {@code TermExplainResponse} is snake_case).
 *
 * {@code plainDefinition} is used by {@link com.finsight.term.TermService} as
 * a fallback whenever the term isn't one of the ~20 seeded in the local
 * {@code Term} table — which is most of the time now that keyTerms come from
 * real AI-detected terms rather than a small fixed dictionary.
 */
public record AiTermExplainResponse(
        @JsonProperty("plain_definition") String plainDefinition,
        @JsonProperty("contextual_meaning") String contextExplanation,
        @JsonProperty("market_impact") String marketImpact
) {
}
