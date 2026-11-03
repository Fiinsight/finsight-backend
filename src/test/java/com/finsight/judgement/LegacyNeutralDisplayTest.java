package com.finsight.judgement;
import com.finsight.news.News;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyNeutralDisplayTest {
    @Test void legacyPartialNeutralResultUsesCurrentRulesWithoutMutatingTheSavedRecord() {
        var news=new News("코스피 장 마감", "https://publisher.test/news", "언론사",Instant.parse("2026-10-12T00:00:00Z"),"코스피 지수가 소폭 움직였다.");
        news.setRelatedSymbol("KOSPI");
        var row=new Judgement(null,news,JudgementChoice.NEUTRAL,"큰 변화가 없을 것으로 봤다.");
        row.setTargetSymbol("KOSPI");row.setActualDirection("NEUTRAL");row.setActualChangePercent(.1);
        ReflectionTestUtils.setField(row,"comparisonDate",LocalDate.of(2026,10,13));
        row.setFeedbackText("방향은 맞았지만 변동이 작아 우연일 수 있어요.");
        row.setStructuredFeedback("방향은 맞았지만 변동이 작았어요",new Judgement.FeedbackContent("PARTIAL",java.util.List.of(),"이전 팁"));
        var service=new JudgementService(null,null);
        JudgementHistoryResponse display=ReflectionTestUtils.invokeMethod(service,"history",row);
        assertThat(display.verdict()).isEqualTo("CORRECT");
        assertThat(display.headline()).isEqualTo("중립 예측이 맞았어요");
        assertThat(display.feedbackText()).contains("중립 예측대로").doesNotContain("우연일 수");
        assertThat(row.getFeedbackContent().verdict()).isEqualTo("PARTIAL");
        assertThat(row.getFeedbackHeadline()).contains("변동이 작았어요");
    }
}
