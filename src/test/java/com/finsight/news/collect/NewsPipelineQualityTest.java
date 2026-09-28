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
    void cleansPublisherChromeAndDuplicatedHeadline() {
        ArticleContentExtractor extractor = new ArticleContentExtractor();
        String cleaned = extractor.clean(
                "원화값 강세에도 순항",
                "원화값 강세에도 순항. Google 검색에서 매일경제 기사를 더 자주 볼 수 있습니다. 반도체 수요가 늘었습니다.");

        assertThat(cleaned).doesNotContain("Google 검색", "더 자주 볼 수 있습니다");
        assertThat(cleaned).doesNotContain("원화값 강세에도 순항");
        assertThat(cleaned).contains("반도체 수요가 늘었습니다");
    }

    @Test
    void mapsCommonCompanyAliasesToChartSymbols() {
        NewsSymbolMatcher matcher = new NewsSymbolMatcher();
        assertThat(matcher.match("삼성·SK하닉 실적 전망 상향")).isNull();
        assertThat(matcher.match("삼성전기 AI 기판 투자 확대")).isEqualTo("009150");
        assertThat(matcher.match("SK하닉 공급 확대 기대")).isEqualTo("000660");
        assertThat(matcher.match("AI 서버 기판 투자 확대", "삼성전기가 6.8조원 규모를 투자합니다.")).isEqualTo("009150");
    }

    @Test
    void classifiesNewsByTopicInsteadOfIndexName() {
        NewsCategoryClassifier classifier = new NewsCategoryClassifier();
        assertThat(classifier.classify("원달러 환율 변동성 확대")).isEqualTo("환율·원자재");
        assertThat(classifier.classify("반도체 수출 회복세")).isEqualTo("산업·기술");
        assertThat(classifier.classify("코스피 장 마감 상승")).isEqualTo("국내증시");
        String fxReason = classifier.importanceReason("원달러 환율 변동성 확대", "달러 강세로 수입 원가가 상승했습니다.",
                "경제 지표 및 시장 동향과 관련된 뉴스입니다.", "005930");
        String rateReason = classifier.importanceReason("기준금리 동결 전망", "물가 안정으로 동결 가능성이 커졌습니다.",
                "경제 지표 및 시장 동향과 관련된 뉴스입니다.", null);
        assertThat(fxReason).contains("환율", "원달러 환율과 원자재 가격");
        assertThat(rateReason).contains("금리·물가 환경");
        assertThat(fxReason).isNotEqualTo(rateReason);
        assertThat(classifier.importanceReason("관련 내용", "본문에도 근거가 없습니다.",
                "경제 지표 및 시장 동향과 관련된 뉴스입니다.", null))
                .contains("구체적 근거가 부족");
    }
}
