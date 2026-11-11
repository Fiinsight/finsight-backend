package com.finsight.news.collect;

import com.finsight.briefing.SentimentHint;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Versioned lexical hints shared with the AI rule fallback; never a price forecast. */
@Component
public class NewsSentimentClassifier {
    public record Compound(String pattern, String label) {}
    public record Rules(List<String> positive,List<String> negative,List<String> rateRises,List<String> rateFalls,List<Compound> compounds) {}
    private static final Rules RULES = loadRules();
    private record Compiled(Pattern pattern, String label) {}
    private static final List<Compiled> COMPOUNDS = RULES.compounds().stream().map(row -> new Compiled(Pattern.compile(row.pattern()),row.label())).toList();
    private static Rules loadRules() {
        try (var stream=NewsSentimentClassifier.class.getResourceAsStream("/qa/sentiment-rules.json")) {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(stream,Rules.class);
        } catch (Exception error) { throw new IllegalStateException("Sentiment rules unavailable",error); }
    }
    public SentimentHint classify(String title) {
        if(title==null || title.isBlank()) return SentimentHint.NEUTRAL;
        boolean rises=RULES.rateRises().stream().anyMatch(title::contains),falls=RULES.rateFalls().stream().anyMatch(title::contains);
        if(rises != falls) return rises ? SentimentHint.NEGATIVE : SentimentHint.POSITIVE;
        if(title.contains("환율") && !title.contains("수출") && !title.contains("수입")) return SentimentHint.NEUTRAL;
        boolean positive=false,negative=false;
        String text=title;
        for(var compound:COMPOUNDS) {
            var match=compound.pattern().matcher(text);
            if(match.find()) {
                positive |= compound.label().equals("POSITIVE"); negative |= compound.label().equals("NEGATIVE");
                text=match.replaceAll(" ");
            }
        }
        final String remaining=text;
        positive |= RULES.positive().stream().anyMatch(remaining::contains);
        negative |= RULES.negative().stream().anyMatch(remaining::contains);
        return positive==negative ? SentimentHint.NEUTRAL : positive ? SentimentHint.POSITIVE : SentimentHint.NEGATIVE;
    }
}
