package com.finsight.chart;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ChartTextEvidenceTest {
    @Test void ownCompanyEventBeatsAnotherCompanyMatchingThePriceDirection() {
        String source="SK하이닉스 주가가 하락했다. 삼성전자는 공급 계약을 발표했다. 삼성전자 주가가 올랐다.";
        assertThat(ChartTextEvidence.lead("005930","삼성전자 계약 발표",source,LocalDate.parse("2026-11-02"),-4,"DIRECT"))
                .isEqualTo("삼성전자는 공급 계약을 발표했다.");
    }
    @Test void wrongCompanyNeverBecomesDirectEvidenceAndSectorSubjectStaysHonest() {
        assertThat(ChartTextEvidence.lead("005930","삼성전자 실적 발표","SK하이닉스가 4% 하락했다.",LocalDate.parse("2026-11-02"),-4,"DIRECT"))
                .isEqualTo("삼성전자 실적 발표");
        assertThat(ChartTextEvidence.lead("005930","반도체 업황 발표","반도체 업계가 수요를 발표했다.",LocalDate.parse("2026-11-02"),-4,"SECTOR"))
                .contains("반도체");
    }
    @Test void causalSourceAndCalculatedMarketComparisonAreExplicitWithoutInventedCauses() {
        var article=new com.finsight.external.AiDocentRequest.Article(1L,"삼성전자, 외국인 매도세에 4.8% 하락",
                "삼성전자는 외국인 매도세에 4.8% 하락했다.",java.time.Instant.parse("2026-11-02T03:00:00Z"),"DIRECT","합성");
        var request=new com.finsight.external.AiDocentRequest("005930","삼성전자","2026-11-02T15:30:00",-4.8,"D",java.util.List.of(article),"beginner","코스피",-1.2);
        var result=ChartDocentService.rule(request);
        assertThat(result.possibleReason()).contains("외국인 매도세에","원인 후보로 볼 수 있어요","코스피는 -1.20%","삼성전자는 -4.80%","3.60%p");
        assertThat(result.marketImpact()).contains("-3.60%p","외국인·기관 매매");
        assertThat(result.possibleReason()).doesNotContain("때문에","차익 실현");
    }
    @Test void lackOfEvidenceAndSingleForeignerKeywordDoNotInventSupplyDemandExplanations() {
        var r=new com.finsight.external.AiDocentRequest("005930","삼성전자","2026-11-02T15:30:00",-4,"W",java.util.List.of(),"normal",null,null);
        var result=ChartDocentService.rule(r);
        assertThat(result.whatHappened()).startsWith("이 주간");
        assertThat(result.possibleReason()).contains("찾지 못했어요").doesNotContain("수급","이날");
        assertThat(result.marketImpact()).isEqualTo("추가로 확인할 시장 자료가 없어요.");
        var article=new com.finsight.external.AiDocentRequest.Article(1L,"삼성전자 외국인 임원 소개","삼성전자는 외국인 임원을 소개했다.",java.time.Instant.parse("2026-11-02T03:00:00Z"),"DIRECT","합성");
        assertThat(ChartTextEvidence.impact(r,article,article.summary())).doesNotContain("매매","수급");
    }
    @Test void anotherCompanyEventInAMarketReportIsNotThisStocksGoodNewsOrEarnings() {
        var a=new com.finsight.external.AiDocentRequest.Article(264L,"코스피 하락…삼성전자 호실적에도 약세",
                "코스피 지수는 하락했다. 삼성전자는 실적을 발표했다.",java.time.Instant.parse("2026-10-12T02:45:00Z"),"MARKET","합성");
        var r=new com.finsight.external.AiDocentRequest("051910","LG화학","2026-10-12T15:30:00",-9.08,"D",java.util.List.of(a),"normal","코스피",-3.65);
        var d=ChartDocentService.rule(r);
        assertThat(d.possibleReason()).contains("시장 관련 보도","-9.08%","-3.65%").doesNotContain("호재","악재","실적");
        assertThat(d.marketImpact()).doesNotContain("실적","예상치","계약");
    }
    @Test void causeQuotesACompleteClauseAndWhatHappenedPreservesTheSourceAtEveryLevel() {
        String source="카카오는 실적 발표와 자사주 소각 계획을 공개했다.";
        var a=new com.finsight.external.AiDocentRequest.Article(635L,"카카오, 실적·자사주 소각 계획 발표에 강세",source,
                java.time.Instant.parse("2026-11-05T00:37:00Z"),"DIRECT","합성");
        for(String level:java.util.List.of("beginner","normal","analyst")) {
            var r=new com.finsight.external.AiDocentRequest("035720","카카오","2026-11-05T15:30:00",8.93,"D",java.util.List.of(a),level,"코스피",-4.);
            var d=ChartDocentService.rule(r);
            assertThat(d.possibleReason()).contains("발표에 강세’","원인 후보").doesNotContain("발표에’","표현처럼");
            assertThat(d.whatHappened()).isEqualTo(source);
        }
    }

}
