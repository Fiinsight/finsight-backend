package com.finsight.term;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TermBoundaryCacheTest {
    @Test void cachedMatchingKeepsOriginalSpacingParticlesCaseAndLiteralRules() {
        String particles = "은|는|이|가|을|를|의|와|과|에|로|도|만|으로|에서|에게|까지|부터|보다|처럼|에도|에는|에선|으로는|에서는|이며|이고|이다|입니다";
        for (String term : List.of("금리", "국채 금리", "PER", "AI", "PF", "A+B", "(특수)", "영업이익", "Σ", "İ", "a\tb")) {
            String literal = java.util.Arrays.stream(term.split(" ")).map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s*"));
            Pattern reference = Pattern.compile("(?<![가-힣A-Za-z0-9])("+literal+")(?=$|[^가-힣A-Za-z0-9]|(?:"+particles+")(?=$|[^가-힣A-Za-z0-9]))", Pattern.CASE_INSENSITIVE);
            for (String text : List.of(term, term+"은 올랐다.", "앞"+term, term+"화", term.toLowerCase(), term.replace(" ",""), term.replace(" ","\n\t"), "관련 없음")) {
                boolean expected = reference.matcher(text).find();
                for (int repeat=0;repeat<3;repeat++) {
                    assertEquals(expected, KeyTermSelector.containsTerm(text,term),text);
                    assertEquals(expected, KeyTermSelector.matchingTerms(text).test(term),text);
                }
            }
        }
        assertFalse(KeyTermSelector.containsTerm(null,"금리"));
        assertFalse(KeyTermSelector.containsTerm("금리",null));
    }
}
