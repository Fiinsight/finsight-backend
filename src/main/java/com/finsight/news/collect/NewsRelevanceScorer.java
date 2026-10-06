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
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(NewsCandidate::score).reversed())
                .limit(topN)
                .toList();
    }

    private NewsCandidate score(NewsCandidate candidate) {
        return candidate.withScore(scoreTitle(candidate.title()));
    }

    public int scoreTitle(String title) {
        return keywordCount(title);
    }

    public int scoreArticle(String title, String body) {
        String headline = title == null ? "" : title;
        String content = body == null ? "" : body;
        int score = 3 * keywordCount(headline) + keywordCount(content);
        if (List.of("기준금리", "물가", "코스피", "코스닥", "환율", "고용").stream().anyMatch(headline::contains)) score += 2;
        if (List.of("부고", "인사", "휴관", "행사 안내", "모집", "공지", "협찬").stream().anyMatch(headline::contains)) score -= 8;
        return score;
    }

    private int keywordCount(String text) {
        if (text == null) return 0;
        int score = 0;
        String remaining = text;
        for (String keyword : SCORING_KEYWORDS.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (remaining.contains(keyword)) {
                score++;
                remaining = remaining.replace(keyword, " ");
            }
        }
        return score;
    }
}
