package com.finsight.term;

import jakarta.validation.constraints.NotBlank;

public record TermExplainRequest(
        @NotBlank String term,
        Long newsId,
        @jakarta.validation.constraints.Pattern(regexp = "beginner|normal|analyst") String level
) {
    public TermExplainRequest(String term, Long newsId) { this(term, newsId, "normal"); }
}
