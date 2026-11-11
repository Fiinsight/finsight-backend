package com.finsight.term;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KeyTermSelectorTest {
    @Test void everydayWordsAndIndustryNamesNeverFillEmptySlots() {
        assertThat(KeyTermSelector.select("주식 시장 기업 투자 경제", "가격 상승 하락 회사 정부 반도체 AI 수출 수입 매출", "analyst")).isEmpty();
    }
    @Test void difficultyFrequencyAndTitleDetermineAtMostSixTerms() {
        String body="기준금리 환율 영업이익 PER 공매도 유동성 밸류업 양적긴축 듀레이션 EPS";
        assertThat(KeyTermSelector.select("",body,"beginner")).containsExactly("기준금리","환율","영업이익");
        assertThat(KeyTermSelector.select("",body,"normal")).contains("PER","공매도").doesNotContain("양적긴축").hasSize(6);
        assertThat(KeyTermSelector.select("양적긴축",body,"analyst")).startsWith("양적긴축").hasSize(6);
        assertThat(KeyTermSelector.select("", "환율 환율 환율 금리", "beginner")).startsWith("환율");
    }
    @Test void longestTermsParticlesAndSpaceVariantsDoNotMatchInsideNouns() {
        assertThat(KeyTermSelector.select("", "저금리와 금리인하는 다르다. 기준금리는 유지됐다. 영업이익률은 개선됐다. 실적가이던스는 공개됐다.", "normal"))
                .contains("기준금리","영업이익률","실적가이던스").doesNotContain("금리","영업이익");
        assertThat(KeyTermSelector.definition("실적 가이던스")).isPresent();
    }
}
