package com.finsight.term;

import jakarta.validation.constraints.NotBlank;

public record TermExplainRequest(
        @NotBlank String term,
        Long newsId
) {
}
