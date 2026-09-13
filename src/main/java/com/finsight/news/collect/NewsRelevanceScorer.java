package com.finsight.news.collect;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scores candidates by how many hardcoded Korean economic/investment
 * keywords appear in the title, and ranks/limits to the most relevant ones.
 */
@Component
public class NewsRelevanceScorer {

    private static final List<String> SCORING_KEYWORDS = List.of(
            "금리", "환율", "실적", "코스피", "코스닥", "반도체", "수출", "물가",
            "공시", "배당", "증시", "주가", "투자", "인플레이션", "무역", "성장률",
            "기준금리", "채권", "유가", "고용"
    );

    public List<NewsCandidate> rank(List<NewsCandidate> candidates, int topN) {
        return candidates.stream()
                .map(this::score)
                .sorted(Comparator.comparingInt(NewsCandidate::score).reversed())
                .limit(topN)
                .toList();
    }

    private NewsCandidate score(NewsCandidate candidate) {
        int score = 0;
        String title = candidate.title();
        for (String keyword : SCORING_KEYWORDS) {
            if (title.contains(keyword)) {
                score++;
            }
        }
        return candidate.withScore(score);
    }
}
