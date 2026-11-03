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

    /**
     * Ranks within each source, then takes turns across sources so the feed
     * with the most entries (Yonhap: ~120 vs ~50) cannot fill every slot.
     */
    public List<NewsCandidate> rank(List<NewsCandidate> candidates, int topN) {
        java.util.Map<String, java.util.ArrayDeque<NewsCandidate>> bySource = new java.util.LinkedHashMap<>();
        candidates.stream()
                .map(this::score)
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(NewsCandidate::score).reversed())
                .forEach(candidate -> bySource.computeIfAbsent(publisher(candidate), ignored -> new java.util.ArrayDeque<>()).add(candidate));
        List<NewsCandidate> ranked = new java.util.ArrayList<>();
        while (ranked.size() < topN && bySource.values().stream().anyMatch(queue -> !queue.isEmpty())) {
            for (var queue : bySource.values()) {
                if (!queue.isEmpty() && ranked.size() < topN) ranked.add(queue.poll());
            }
        }
        return List.copyOf(ranked);
    }

    private static String publisher(NewsCandidate candidate) {
        try {
            String host = java.net.URI.create(candidate.url()).getHost();
            if (host != null) return host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException ignored) {
            // Fall back to the feed title for malformed links.
        }
        return String.valueOf(candidate.source());
    }

    private NewsCandidate score(NewsCandidate candidate) {
        return candidate.withScore(scoreTitle(candidate.title()));
    }

    public int scoreTitle(String title) {
        return keywordCount(title) + localPenalty(title);
    }

    public int scoreArticle(String title, String body) {
        String headline = title == null ? "" : title;
        String content = body == null ? "" : body;
        int score = 3 * keywordCount(headline) + keywordCount(content);
        if (List.of("기준금리", "물가", "코스피", "코스닥", "환율", "고용").stream().anyMatch(headline::contains)) score += 2;
        if (List.of("부고", "인사", "휴관", "행사 안내", "모집", "공지", "협찬").stream().anyMatch(headline::contains)) score -= 8;
        return score + localPenalty(headline);
    }

    private int localPenalty(String title) {
        if (title == null) return 0;
        boolean local = title.matches(".*(시장|군수|도지사|시청|군청|도청|구청).*" )
                || title.matches(".*(속초|새만금|수출탑|분쟁조정위).*" );
        boolean finance = title.matches(".*(코스피|코스닥|증시|주가|상장|실적|영업이익|채권|기준금리).*" );
        return local && !finance ? -5 : 0;
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
