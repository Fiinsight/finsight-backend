package com.finsight.chart;

import com.finsight.news.News;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RelatedNewsTierTest {
    private static final Instant START = Instant.parse("2026-11-02T06:30:00Z");
    private static final Instant END = Instant.parse("2026-11-03T06:30:00Z");
    private News article(String title, String body, Instant at) {
        return new News(title, "https://fixture.test/" + title, "fixture", at, body);
    }

    @Test void directThenSectorThenMarketFallbackRetainsHonestLabels() {
        var direct = article("삼전 실적 발표", "삼성전자 영업이익이 늘었다.", END.minusSeconds(3600));
        var sector = article("반도체 수출 회복", "메모리 업황이 개선됐다.", END.minusSeconds(1800));
        var market = article("코스피 전망", "시장 흐름을 살폈다.", END.minusSeconds(600));
        assertThat(ChartService.relevanceRank("005930", direct)).isEqualTo(3);
        assertThat(ChartService.relevanceRank("005930", sector)).isEqualTo(2);
        assertThat(ChartService.relevanceRank("005930", market)).isEqualTo(1);
        assertThat(ChartService.candidateNews("005930", List.of(market,sector,direct), START, END)).containsExactly(direct);
        assertThat(ChartService.candidateNews("005930", List.of(market,sector), START, END)).containsExactly(sector);
        assertThat(ChartService.candidateNews("005930", List.of(market), START, END)).containsExactly(market);
    }

    @Test void bodyAliasesWorkButSubsidiaryAndGroupNamesAreNotDirectMatches() {
        assertThat(ChartService.relevanceRank("373220", article("기업 실적", "LG엔솔이 실적을 발표했다.", END))).isEqualTo(3);
        assertThat(ChartService.relevanceRank("000660", article("기업 실적", "하이닉스가 영업이익을 발표했다.", END))).isEqualTo(3);
        assertThat(ChartService.relevanceRank("005930", article("삼성 실적", "그룹 소식입니다.", END))).isZero();
        assertThat(ChartService.relevanceRank("035720", article("카카오뱅크 실적", "은행 소식입니다.", END))).isZero();
    }

    @Test void equallyScoredRecentNewsWinsAndThePreviousTradingCloseIsIncluded() {
        var older = article("코스피 전망", "시장 흐름", START);
        var recent = article("코스피 전망", "시장 흐름", END.minusSeconds(60));
        assertThat(ChartService.candidateNews("005930", List.of(older,recent), START, END)).containsExactly(recent,older);
        var monday = ChartService.moveWindow(java.time.LocalDate.of(2026,11,2), "D");
        assertThat(monday.from()).isEqualTo(Instant.parse("2026-10-30T06:30:00Z"));
    }

    @Test void sectorDocentCannotClaimDirectCompanyCausation() {
        var request = new com.finsight.external.AiDocentRequest("005930", "삼성전자", "2026-11-03", 3,
                "D", List.of(new com.finsight.external.AiDocentRequest.Article(1L,"반도체 수출 회복","업황 개선",END,"SECTOR","fixture",null,null)),
                "normal", "코스피", null);
        assertThat(ChartDocentService.rule(request).relation()).isEqualTo("SECTOR");
        assertThat(ChartDocentService.rule(request).possibleReason()).contains("직접 다룬 기사가 아니므로");
    }
}
