package com.finsight.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsPipelineQualityTest {

    @Test
    void dropsUnrelatedTitlesBeforeArticleExtraction() {
        NewsRelevanceScorer scorer = new NewsRelevanceScorer();
        List<NewsCandidate> ranked = scorer.rank(List.of(
                new NewsCandidate("박물관 휴관 안내", "https://example.com/museum", "feed", Instant.now(), 0),
                new NewsCandidate("반도체 수출 증가로 증시 기대감", "https://example.com/market", "feed", Instant.now(), 0)), 10);

        assertThat(ranked).extracting(NewsCandidate::title).containsExactly("반도체 수출 증가로 증시 기대감");
    }

    @Test
    void rejectsSearchChromeAndShortBodies() {
        ArticleContentExtractor extractor = new ArticleContentExtractor();
        assertThat(extractor.isUsable("경제 뉴스", "Google 검색 결과를 표시합니다.".repeat(30))).isFalse();
        assertThat(extractor.isUsable("경제 뉴스", "금리와 환율이 올랐습니다.")).isFalse();
        assertThat(extractor.isUsable("반도체 수출", "반도체 수출이 증가했습니다. ".repeat(12))).isTrue();
    }
}
