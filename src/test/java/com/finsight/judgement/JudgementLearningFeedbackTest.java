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
        assertThat(result.reasons().get(1).detail()).contains("남긴 근거 “실적 부진을 예상했어요.”", "탄탄했어요");
        assertThat(result.reasons().get(0).detail()).contains("악재 신호", "하락 방향도 같았어요");
        assertThat(result.summary()).contains("개별 소식의 영향이 더 컸을 수").doesNotContain("때문에 하락");
        assertThat(result.nextTip()).contains("실적 발표 일정");
    }
    @Test void earningsAndOrderFlowExplainThePriceChannelInsteadOfOnlyAlignment() {
        var earnings = JudgementFeedbackRules.generate(request("실적 부진", "실적 부진", "매출이 감소했다.",-.2));
        assertThat(earnings.reasons().getFirst().detail()).contains("이익에 대한 기대를 낮춰", "팔려는 수요");
        assertThat(JudgementFeedbackRules.priceChannel("매출 증가","POSITIVE")).contains("이익에 대한 기대를 높여", "상승 요인");
        assertThat(JudgementFeedbackRules.priceChannel("외국인 매도 확대","NEGATIVE")).contains("수요", "공급", "매도 압력");
        assertThat(JudgementFeedbackRules.signal("기준금리 상승",null,null)).isEqualTo("NEGATIVE");
        assertThat(JudgementFeedbackRules.signal("환율 상승",null,null)).isEqualTo("NEUTRAL");
    }
    @Test void correctDirectionDoesNotTurnWeakOrContradictoryReasonsIntoGoodReasoning() {
        var weak = JudgementFeedbackRules.generate(request("하락할 것 같아요", "실적 부진", "매출이 감소했다.",-.2));
        assertThat(weak.verdict()).isEqualTo("CORRECT");
        assertThat(weak.reasons()).extracting(com.finsight.external.AiFeedbackResponse.Reason::title)
                .containsExactly("기사 신호와 결과","내 근거 점검","시장 비교");
        assertThat(weak.reasons().get(1).detail()).contains("남긴 근거 “하락할 것 같아요”", "운이 섞였을 수", "확인한 사실", "가격의 연결");
        var conflict = JudgementFeedbackRules.generate(request("매출 증가를 예상했어요", "매출 증가", "매출이 증가했다.",-.2));
        assertThat(conflict.reasons().get(1).detail()).contains("운이 섞였을 수", "모순");
    }
    @Test void soundReasoningCanMissTheOutcomeAndExplainsTheSameCauseAsArticleReason() {
        var result = JudgementFeedbackRules.generate(new AiFeedbackRequest(1L,"UP","매출 증가로 이익 개선을 기대해요.","DOWN",
                "매출 증가","매출이 증가했다.","005930","삼성전자",-4.4,null,null,LocalDate.parse("2026-10-13"),"normal","코스피",-3.7,null,null,null,null,1L));
        assertThat(result.verdict()).isEqualTo("WRONG");
        assertThat(result.reasons().get(1).detail()).contains("타당한 추론", "시장 전체 하락에 묻혔을 수 있어요", "좋은 근거도");
        assertThat(result.reasons().getFirst().detail()).contains("시장 전체 하락에 묻혔을 수 있어요");
    }
    @Test void reviewComparesChoiceWithSignalAndChecksFactsAndPriceLinkWithoutGrading() {
        var neutral = article("상담 서비스 안내","고객센터 상담 서비스가 운영된다.");
        var absent = JudgementFeedbackRules.articleReview(neutral,null,JudgementChoice.UP).get(1).detail();
        assertThat(absent).contains("기사에 뚜렷한 방향 신호가 없는데 상승을 골랐어요", "다음엔 판단 근거를 한 줄 남겨보세요", "“고객센터 상담 서비스가 운영된다” → 이익·수요·금리 변화 → 예상 방향")
                .doesNotContain("탄탄", "타당", "잘했", "정확한 판단");
        assertThat(absent.split("다음엔 판단 근거를 한 줄 남겨보세요",-1)).hasSize(2);
        assertThat(absent).doesNotContain("순서로 한 줄을 써보세요");
        assertThat(JudgementFeedbackRules.articleReview(neutral,null,JudgementChoice.UP).getFirst().detail()).contains("중립으로 읽혀요");
        var strong = JudgementFeedbackRules.articleReview(article("매출 증가","매출이 증가했다."),"매출 증가로 이익 개선을 기대해요.",JudgementChoice.UP).get(1).detail();
        assertThat(strong).contains("호재 신호와 상승 선택은 같은 방향", "기사 표현과 가격 경로를 연결", "적중 여부는 아직 알 수 없어요");
        var weak = JudgementFeedbackRules.articleReview(neutral,"그냥 상승할 것 같아요",JudgementChoice.UP).get(1).detail();
        assertThat(weak).contains("확인한 사실", "가격의 연결");
    }
    @Test void interpretationDistinguishesSmallMovesMarketMaskingAndPricedInNews() {
        assertThat(JudgementFeedbackRules.interpretation("POSITIVE","NEUTRAL",.5,-3.7)).contains("크게 반영하지 않았을 수");
        assertThat(JudgementFeedbackRules.interpretation("POSITIVE","DOWN",-2.,-.5)).contains("선반영", "다른 악재").doesNotContain("묻혔");
        assertThat(JudgementFeedbackRules.interpretation("NEGATIVE","UP",2.,0.)).contains("선반영", "안도감");
        assertThat(JudgementFeedbackRules.interpretation("NEUTRAL","DOWN",-2.,0.)).contains("시장 흐름이나 수급");
    }
}
