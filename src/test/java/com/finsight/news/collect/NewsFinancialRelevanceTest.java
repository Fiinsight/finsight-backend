package com.finsight.news.collect;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class NewsFinancialRelevanceTest {
    @Test void corporateResultsAndFinancialMarketsRemainEligible() {
        var scorer = new NewsRelevanceScorer();
        var titles = List.of("삼성전자 3분기 영업이익 9조", "외환시장 불안에 원화 약세", "시장금리 상승세",
                "부동산시장 거래 회복", "삼성전자 매출 증가", "기업 순이익 확대", "조선사 수주 계약 체결", "대형사 인수 합병 공시");
        for (String title : titles) assertThat(scorer.scoreTitle(title)).as(title).isPositive();
        var candidates = titles.stream().map(title -> new NewsCandidate(title, "https://fixture.test/"+title, "경제 피드", Instant.EPOCH, 0)).toList();
        assertThat(scorer.rank(candidates, 50)).hasSize(titles.size());
    }

    @Test void mayorAndMunicipalOfficeNoticesRemainPenalized() {
        var scorer = new NewsRelevanceScorer();
        for (String title : List.of("속초시장, 축제 개최", "서울시장, 시민 체육대회 참석", "부산시청 행사 안내", "양양군수, 지역 기념식 참석"))
            assertThat(scorer.scoreTitle(title)).as(title).isNegative();
    }
}
