package com.finsight.external;
import com.fasterxml.jackson.annotation.JsonProperty;
/** Collection requests omit level and receive all three summaries in one call. */
public record AiRewriteRequest(String title, String body, String level, @JsonProperty("article_key") String articleKey, @JsonProperty("allow_llm") boolean allowLlm) {
    public AiRewriteRequest(String title, String body, String level) { this(title, body, level, null, true); }
    public AiRewriteRequest(String title, String body, String level, String key) { this(title, body, level, key, true); }
}
