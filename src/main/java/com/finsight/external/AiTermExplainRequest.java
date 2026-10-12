package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body sent to the AI service's POST /ai/terms/explain endpoint. */
public record AiTermExplainRequest(String term, @JsonProperty("article_context") String context, @JsonProperty("known_definition") String knownDefinition, String level) {
    public AiTermExplainRequest(String term, String context, String definition) { this(term, context, definition, "normal"); }
    public AiTermExplainRequest(String term, String context) { this(term, context, null, "normal"); }
}
