package com.finsight.chart;
import com.finsight.news.News;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChartCauseRankingTest {
    static final Instant FROM=Instant.parse("2026-10-30T06:30:00Z"), TO=Instant.parse("2026-11-02T06:30:00Z");
    private News news(long id,String title,String symbol) {
        var n=new News(id,title,"https://fixture.test/"+id,"합성",FROM.plusSeconds(3600),"시장",symbol,"",title+". 상세 사건을 발표했다.");
        return n;
    }
    @Test void weekendAgeIsCappedAndNeverOverwhelmsContent() {
        assertThat(ChartService.tradingTimePenalty(FROM.plusSeconds(3600),TO)).isEqualTo(.3);
        assertThat(ChartService.tradingTimePenalty(FROM.minus(Duration.ofDays(30)),TO)).isEqualTo(1.5);
    }
    @Test void marketFollowingKeepsMarketEvidenceEvenWithCorporateNewsAndExcludesOppositeAndLowSignal() {
        var market=news(1,"코스피 외국인 매도에 하락",null);
        var sector=news(2,"반도체 수요 감소 우려",null);
        var direct=news(3,"삼성전자 영업이익 발표","005930");
        var exhibition=news(4,"삼성전자 전시회 개최","005930");
        var opposite=news(5,"삼성전자 호실적에 급등","005930");
        assertThat(ChartService.candidateNews("005930",List.of(direct,market,sector,exhibition,opposite),FROM,TO,-4,"FOLLOWING"))
                .containsExactly(market,sector,direct);
        assertThat(ChartService.candidateNews("005930",List.of(opposite),FROM,TO,-4,"INDIVIDUAL")).isEmpty();
    }
    @Test void marketContextUsesTheSameDifferenceBoundaryAsJudgementFeedback() {
        for(double move:List.of(-1.01,-1.5,-2.0,-3.1)) {
            String context=ChartService.marketContext(move,-2.0,com.finsight.judgement.JudgementFeedbackRules.MARKET_DIFFERENCE_PERCENT);
            assertThat(context.equals("FOLLOWING")).isEqualTo("MARKET".equals(com.finsight.judgement.JudgementFeedbackRules.marketComparison(move,-2)));
        }
        assertThat(ChartService.marketContext(2,-1.0,1)).isEqualTo("OPPOSITE");
        assertThat(ChartService.marketContext(2,null,1)).isEqualTo("UNKNOWN");
    }
    @Test void macroNewsRequiresEquityMarketContextAndGenericPlatformIsNotASector() {
        assertThat(ChartService.relevanceRank("005930",news(1,"미국 FOMC 금리 발표에 증시 약세",null))).isEqualTo(1);
        assertThat(ChartService.relevanceRank("005930",news(2,"환율 상승과 물가 전망",null))).isZero();
        assertThat(ChartService.relevanceRank("035420",news(3,"인터넷 신청 플랫폼 개설 안내",null))).isZero();
        assertThat(ChartService.relevanceRank("035420",news(4,"검색 광고 업황 개선",null))).isEqualTo(2);
    }
    @Test void onlySameCompanySameMoveAfterCloseReportsMayExplainAMoveAndKeepReferenceMarkers() {
        var window=ChartService.moveWindow(java.time.LocalDate.parse("2026-11-02"),"D");
        var report=new News(1L,"삼성전자, 이날 수출 우려에 4% 하락 마감","https://fixture.test/close","합성",window.to().plusSeconds(600),"시장","005930","","삼성전자는 이날 수출 우려에 4% 하락 마감했다.");
        var next=new News(2L,"삼성전자 다음주 상승 전망","https://fixture.test/future","합성",window.to().plusSeconds(600),"시장","005930","","삼성전자는 다음주 상승할 전망이다.");
        assertThat(ChartService.isAfterCloseExplanation("005930",report,window)).isTrue();
        assertThat(ChartService.isAfterCloseExplanation("005930",next,window)).isFalse();
        assertThat(ChartService.isAfterCloseExplanation("000660",report,window)).isFalse();
        assertThat(ChartService.moveCandidates("005930",List.of(report,next),window,-4,"INDIVIDUAL")).containsExactly(report);
    }
}
