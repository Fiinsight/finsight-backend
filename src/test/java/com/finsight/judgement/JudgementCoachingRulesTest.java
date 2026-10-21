package com.finsight.judgement;

import com.finsight.external.AiFeedbackRequest;
import com.finsight.external.AiFeedbackResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Regression examples mirrored in Python test_feedback_reasoning_rules.py. */
class JudgementCoachingRulesTest {
    @Test void sameCoachingContractAsPython() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var input=getClass().getResourceAsStream("/feedback_coaching_contract.json")) {
            for(var value:json.readTree(input)) {
                var result=JudgementFeedbackRules.generate(new AiFeedbackRequest(1L,value.path("choice").asText(),value.path("reason").isNull()?null:value.path("reason").asText(),
                        value.path("direction").asText(),value.path("title").asText(),value.path("lead").asText(),"005930","삼성전자",value.path("percent").asDouble(),
                        null,null,LocalDate.parse("2026-10-13"),value.path("level").asText(),"코스피",value.path("index").asDouble(),null,null,null,null,1L));
                assertThat(result.verdict()).as(value.path("case").asText()).isEqualTo(value.path("verdict").asText());
                com.fasterxml.jackson.databind.JsonNode actualReasons=json.valueToTree(result.reasons());
                assertThat(actualReasons).as(value.path("case").asText()).isEqualTo(value.path("reasons"));
                assertThat(result.nextTip()).as(value.path("case").asText()).isEqualTo(value.path("nextTip").asText());
            }
        }
    }
    private AiFeedbackResponse feedback(String reason, String choice, String direction, double percent, String title, String summary, double index) {
        return JudgementFeedbackRules.generate(new AiFeedbackRequest(1L,choice,reason,direction,title,summary,"005930","삼성전자",percent,
                null,null,LocalDate.parse("2026-10-13"),"beginner","코스피",index,null,null,null,null,1L));
    }
    private AiFeedbackResponse feedback(String reason, String choice, String direction, double percent, double index) {
        return feedback(reason,choice,direction,percent,"매출 증가","매출이 전년보다 증가했다.",index);
    }
    @ParameterizedTest @ValueSource(strings={"유가","유가가 올라서","유가가 많이 올라서"})
    void oneFactCannotAlsoSupplyThePriceLink(String reason) {
        var quality=JudgementFeedbackRules.reasonQuality(reason,"UP","유가 상승 국제 유가가 상승했다.");
        assertThat(quality.hasFact()).isTrue();assertThat(quality.hasLink()).isFalse();assertThat(quality.strong()).isFalse();
        var result=feedback(reason,"UP","UP",2.3,"유가 상승","국제 유가가 상승했다.",.4);
        assertThat(result.reasons().get(1).detail()).contains("운이 섞였을 수").doesNotContain("탄탄","타당","잘했","훌륭");
        assertThat(JudgementFeedbackRules.reasonQuality("매출 이익","UP","매출 증가").strong()).isFalse();
        assertThat(JudgementFeedbackRules.reasonQuality("매출 이익.....","UP","매출 증가").strong()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"성장 기대가 커져서","외국인 매수세가 늘어날 것"})
    void reasonHasItsOwnDirectionAndCannotContradictChoice(String reason) {
        var result=feedback(reason,"DOWN","DOWN",-2,reason,reason+"으로 관측됐다.",.4);
        assertThat(result.reasons().get(1).detail()).contains("근거는 상승 쪽 이야기인데 하락을 골랐어요");
        assertThat(JudgementFeedbackRules.reasonQuality(reason,"DOWN",reason).strong()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"부담이 줄어 이익이 늘 것","비용이 감소해 이익이 늘 것","줄어드는 부담으로 이익 회복"})
    void reducedBurdenIsPositive(String reason) {
        assertThat(JudgementFeedbackRules.reasonDirection(reason)).isEqualTo("UP");
        assertThat(JudgementFeedbackRules.reasonQuality(reason,"UP","원가 부담 완화").contradicts()).isFalse();
        assertThat(feedback(reason,"UP","UP",2,.4).reasons().get(1).detail()).doesNotContain("하락 쪽 이야기");
    }
    @Test void neutralNewsWithLargeStockIndexGapExplainsIndividualFactorsInBothReasons() {
        for(String choice:new String[]{"UP","DOWN"}) {
            var result=feedback("정책 일정을 확인했어요.",choice,"UP",2.3,"정책 일정","정책 일정이 공지됐다.",.4);
            assertThat(result.reasons().getFirst().detail()).contains("개별");assertThat(result.reasons().get(2).detail()).contains("개별");
            assertThat(result.feedbackText()).doesNotContain("시장 흐름을 따라","시장 흐름이나 수급을 따라");
        }
    }
    @Test void verdictChangesWhyAndWrongReasonOnlyBrieflyReferencesTheCause() {
        String reason="매출 증가로 이익 개선을 기대해요.";
        var correct=feedback(reason,"UP","UP",2.3,.4);var wrong=feedback(reason,"UP","DOWN",-4.4,-3.7);
        assertThat(correct.reasons().getFirst().detail()).isNotEqualTo(feedback(reason,"DOWN","UP",2.3,.4).reasons().getFirst().detail());
        assertThat(correct.reasons().getFirst().detail()).isNotEqualTo(wrong.reasons().getFirst().detail());
        String article=wrong.reasons().getFirst().detail();String last=article.substring(article.lastIndexOf(". ")+2);
        assertThat(wrong.reasons().get(1).detail()).contains("타당했지만 시장 전체의 반대 움직임").doesNotContain(last);
        assertThat(feedback(reason,"UP","UP",2.3,2).reasons().getFirst().detail()).contains("시장 전체도 같은 방향이라 뉴스만의 효과로 보긴 어려워요");
        assertThat(feedback(reason,"UP","UP",.7,.4).reasons().getFirst().detail()).contains("±1% 이내");
    }
    @ParameterizedTest @CsvSource({"POSITIVE,UP,DOWN,-3,-2,MARKET","POSITIVE,DOWN,UP,2,0,CHOICE","POSITIVE,UP,DOWN,-2,0,PRICED_IN","NEGATIVE,DOWN,UP,2,0,PRICED_IN","NEUTRAL,UP,DOWN,-2,0,NO_SIGNAL"})
    void wrongCausePriority(String signal,String choice,String direction,double percent,double index,String expected) {
        assertThat(JudgementFeedbackRules.missCause(signal,choice,direction,percent,index)).isEqualTo(expected);
    }
    @ParameterizedTest @CsvSource({"NEUTRAL,DOWN,-1.5","NEUTRAL,UP,1.5","UP,NEUTRAL,.3","DOWN,NEUTRAL,-.3"})
    void neutralMismatchExplainsTheHalfPercentBand(String choice,String direction,double percent) {
        assertThat(feedback("매출 증가로 이익 개선을 기대해요.",choice,direction,percent,.4).reasons().get(1).detail())
                .contains("±0.5%",JudgementFeedbackRules.signed(percent));
    }
    @Test void neutralOutcomeStillEvaluatesStrongAndWeakReasons() {
        String strong="매출 증가로 이익 개선을 기대해요.";
        var good=feedback(strong,"UP","NEUTRAL",.3,.4).reasons().get(1).detail();
        assertThat(good).contains("근거는 기사 사실과 가격 경로를 이었지만 다음 거래일에는 시장이 이 소식을 크게 반영하지 않았어요.","±0.5%");
        var weak=feedback("상승할 것 같아요","UP","NEUTRAL",.3,.4).reasons().get(1).detail();
        assertThat(weak).contains("기사에서 본 사실과","±0.5%").doesNotContain("탄탄");
        var neutral=feedback(strong,"NEUTRAL","DOWN",-1.5,.4).reasons().get(1).detail();
        assertThat(neutral).contains("중립은 다음 거래일 등락이 ±0.5% 안일 때 맞아요.").doesNotContain("이 소식을 크게 반영하지 않았어요");
    }
    @Test void quoteGrammarExampleNounsAndBeginnerDefinitionAreSeparateSentences() {
        var result=feedback("매출 증가로 이익 개선을 기대해요.","UP","UP",2.3,.4);
        assertThat(result.reasons().get(1).detail()).contains("남긴 근거: “매출 증가로 이익 개선을 기대해요.”.");
        assertThat(result.feedbackText()).doesNotContain("”와 ","”과 ","”는 ","”은 ");
        assertThat(result.reasons().get(2).detail()).doesNotContain(";").endsWith(" 종가는 거래가 끝날 때의 가격이에요.");
        assertThat(JudgementFeedbackRules.examplePhrase("유가 고영향 물품 조달 안정화 연장하기로 했다.")).isEqualTo("유가 고영향 물품 조달 안정화 연장");
        String longPhrase=JudgementFeedbackRules.examplePhrase("산업통상자원부는 오늘 유가 고영향 물품 조달 안정화 조치를 연장하기로 했다.");
        assertThat(longPhrase).hasSizeLessThanOrEqualTo(25).endsWith("연장");
    }
    @Test void tipsUseTheFirstMissingSkillBeforeMarketOrGenericTips() {
        String[][] cases={{null,"판단 근거를 한 줄"},{"수요 회복으로 이익 확대를 기대해요.","기사에서 확인한 사실"},{"매출이 증가했다는 내용이에요.","무엇을 바꾸는지"},
                {"매출이 떨어져 이익 감소를 우려해요.","고른 방향"},{"매출 악재 이익","고른 방향"},{"매출 증가로 이익 개선을 기대해요.","코스피 흐름"}};
        for(String[] value:cases) assertThat(feedback(value[0],"UP","UP",2.3,2).nextTip()).contains(value[1]);
    }
}
