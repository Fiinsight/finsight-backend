package com.finsight.news.collect;

import com.finsight.briefing.SentimentHint;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Assigns a POSITIVE/NEUTRAL/NEGATIVE hint to a headline via keyword
 * scoring — same "count keyword hits" approach as {@link NewsRelevanceScorer},
 * just tallying positive vs. negative market-tone words instead of relevance.
 * Not sentiment analysis in any NLP sense, just a cheap, keyless heuristic so
 * every collected article isn't stuck showing NEUTRAL.
 */
@Component
public class NewsSentimentClassifier {

    private static final List<String> POSITIVE_KEYWORDS = List.of(
            "상승", "급등", "강세", "호조", "개선", "확대", "성장", "최대", "흑자", "반등",
            "훈풍", "호황", "증가", "상향", "돌파", "회복", "기대감", "역대급"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "하락", "급락", "약세", "부진", "우려", "축소", "위축", "적자", "하향", "둔화",
            "침체", "한파", "감소", "경고", "리스크", "불안", "충격", "쇼크"
    );

    public SentimentHint classify(String title) {
        int score = countHits(title, POSITIVE_KEYWORDS) - countHits(title, NEGATIVE_KEYWORDS);
        if (score > 0) {
            return SentimentHint.POSITIVE;
        }
        if (score < 0) {
            return SentimentHint.NEGATIVE;
        }
        return SentimentHint.NEUTRAL;
    }

    private int countHits(String title, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }
}
