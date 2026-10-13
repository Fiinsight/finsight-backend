package com.finsight.judgement;

import com.finsight.external.AiFeedbackRequest;
import com.finsight.news.News;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JudgementLearningFeedbackTest {
    private News article(String title, String body) { return new News(title,"https://example.test/news","fixture",Instant.now(),body); }
    @Test void aliasesAndDomesticIndexFallbackHaveExplicitTargets() {
        assertThat(JudgementTargetResolver.resolve(article("포스코인터, 에너지 사업 투자 확대","에너지 사업 매출이 증가했다.")).symbol()).isEqualTo("047050");
        assertThat(JudgementTargetResolver.resolve(article("기준금리 동결","금리 정책이 국내 경제에 미치는 영향이다.")).symbol()).isEqualTo("KOSPI");
        assertThat(JudgementTargetResolver.resolve(article("코스닥 수급 변화","코스닥의 외국인 매도 규모가 늘었다.")).symbol()).isEqualTo("KOSDAQ");
        assertThat(JudgementTargetResolver.resolve(article("코스피·코스닥 하락","국내 증시가 하락했다.")).symbol()).isEqualTo("KOSPI");
        assertThat(JudgementTargetResolver.resolve(article("미국 나스닥 상승","미국 기술 기업의 매출이 늘었다.")).symbol()).isNull();
        assertThat(JudgementTargetResolver.resolve(article("[부고] 기업 임원 별세","부고 내용이다.")).symbol()).isNull();
        assertThat(JudgementTargetResolver.resolve(article("고용노동부 AI 상담","카카오톡에서 고용노동부 채널을 검색하면 AI 상담을 이용할 수 있다.")).symbol()).isEqualTo("KOSPI");
    }
    @Test void onePercentagePointIsTheIndividualBoundary() {
        assertThat(JudgementFeedbackRules.marketComparison(-4.4,-3.7)).isEqualTo("MARKET");
        assertThat(JudgementFeedbackRules.marketComparison(-4,-3)).isEqualTo("INDIVIDUAL");
        assertThat(JudgementFeedbackRules.marketComparison(2,1.01)).isEqualTo("MARKET");
        assertThat(JudgementFeedbackRules.marketComparison(2,.99)).isEqualTo("INDIVIDUAL");
    }
    private AiFeedbackRequest request(String reason, String title, String summary, double index) {
        return new AiFeedbackRequest(1L,"DOWN",reason,"DOWN",title,summary,"005930","삼성전자",-4.4,
                java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(95.6),LocalDate.parse("2026-10-13"),"beginner","코스피",index,"POSITIVE",.9,"금리","돈을 빌리는 비용",1L);
    }
    @Test void emptyReasonNeverPraisesAndGoodNewsCanBeMaskedByMarketDecline() {
        var result = JudgementFeedbackRules.generate(request(null,"호실적 발표","매출이 증가했다.",-3.7));
        assertThat(result.headline()+result.summary()+result.reasons()).doesNotContain("정확한 판단", "잘 연결").contains("한 줄 남겨보세요", "시장 전체 하락에 묻혔을 수", "-4.40%", "-3.70%", "시장 전체 흐름과 함께");
        assertThat(result.conceptTerm()).isEqualTo("금리");
        assertThat(result.nextTip()).contains("코스피 흐름");
    }
    @Test void suppliedReasonIsQuotedAndNegativeSignalIsLinkedWithoutCausalCertainty() {
        var result = JudgementFeedbackRules.generate(request("실적 부진을 예상했어요.","실적 부진","매출이 감소했다.",-.2));
        assertThat(result.reasons().get(1).detail()).contains("“실적 부진을 예상했어요.”", "같았어요");
        assertThat(result.reasons().get(0).detail()).contains("악재 신호", "하락 방향도 같았어요");
        assertThat(result.summary()).contains("개별 소식의 영향이 더 컸을 수").doesNotContain("때문에 하락");
        assertThat(result.nextTip()).contains("실적 발표 일정");
    }
}
