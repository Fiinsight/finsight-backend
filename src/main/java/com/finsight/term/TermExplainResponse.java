package com.finsight.term;

public record TermExplainResponse(
        String term,
        String definition,
        String contextExplanation,
        String marketImpact
) {
}
