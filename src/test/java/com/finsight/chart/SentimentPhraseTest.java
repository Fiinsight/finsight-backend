package com.finsight.chart;
import com.finsight.news.SentimentEvidence;
import com.finsight.briefing.SentimentHint;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SentimentPhraseTest {
    @Test void resolutionWithdrawalAndFailedReboundsOverrideTheEmbeddedDirectionWord() {
        for(String title:java.util.List.of("삼성전자 우려 해소","삼성전자 매도 의견 철회","삼성전자 하락 멈춰","삼성전자 하락 딛고 반등"))
            assertThat(SentimentEvidence.titleDirection(title)).isEqualTo(SentimentHint.POSITIVE);
        for(String title:java.util.List.of("삼성전자 하락 딛고 반등 실패","삼성전자 우려 해소 실패"))
            assertThat(SentimentEvidence.titleDirection(title)).isEqualTo(SentimentHint.NEGATIVE);
    }
    @Test void reducedMagnitudeAndNegatedMovesStayUnknownUnlessALaterMoveIsExplicit() {
        for(String title:java.util.List.of("삼성전자 상승폭 축소","삼성전자 낙폭 축소","삼성전자 상승하지 못했다"))
            assertThat(SentimentEvidence.titleDirection(title)).isNull();
        assertThat(SentimentEvidence.titleDirection("삼성전자 낙폭 축소 후 상승 전환")).isEqualTo(SentimentHint.POSITIVE);
        assertThat(SentimentEvidence.direction("NEGATIVE",.6,"삼성전자 우려 해소")).isEqualTo(SentimentHint.NEGATIVE);
    }
}
