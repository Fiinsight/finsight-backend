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

    @Test
    void mapsCommonCompanyAliasesToChartSymbols() {
        NewsSymbolMatcher matcher = new NewsSymbolMatcher();
        assertThat(matcher.match("삼성·SK하닉 실적 전망 상향")).isEqualTo("005930");
        assertThat(matcher.match("SK하닉 공급 확대 기대")).isEqualTo("000660");
    }

    @Test
    void classifiesNewsByTopicInsteadOfIndexName() {
        NewsCategoryClassifier classifier = new NewsCategoryClassifier();
        assertThat(classifier.classify("원달러 환율 변동성 확대")).isEqualTo("환율·원자재");
        assertThat(classifier.classify("반도체 수출 회복세")).isEqualTo("산업·기술");
        assertThat(classifier.classify("코스피 장 마감 상승")).isEqualTo("국내증시");
    }
}
