package com.finsight.judgement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.finsight.auth.User;
import com.finsight.external.AiFeedbackResponse;
import com.finsight.external.AiServiceClient;
import com.finsight.external.kis.*;
import com.finsight.news.News;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeedbackSchedulerTest {
    private final JudgementRepository records = mock(JudgementRepository.class);
    private final KisDailyCandleClient candles = mock(KisDailyCandleClient.class);
    private final AiServiceClient ai = mock(AiServiceClient.class);
    private final FeedbackScheduler scheduler = new FeedbackScheduler(records, candles, ai);
    @org.junit.jupiter.api.BeforeEach void clock() {
        when(records.finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "clock", Clock.fixed(Instant.parse("2026-10-06T07:00:00Z"), ZoneId.of("Asia/Seoul")));
    }
    private Judgement pending(String symbol, String date) {
        var news = spy(new News("학습용 기사", "https://example.invalid/qa", "SYNTHETIC_QA", Instant.now(), "QA"));
        when(news.getId()).thenReturn(115L); news.setRelatedSymbol(symbol);
        var item = spy(new Judgement(mock(User.class), news, JudgementChoice.UP, "근거"));
        when(item.getCreatedAt()).thenReturn(Instant.parse(date + "T01:00:00Z"));
        when(records.findPendingByOwner(eq(1L), any())).thenReturn(List.of(item));
        return item;
    }
    private KisDailyCandle bar(String date, double close) { return new KisDailyCandle(LocalDate.parse(date), close, close, close, close); }
    @Test void startupProcessesPastComparisonAndSkipsFutureComparison() {
        var past = pending(null, "2026-09-28");
        var future = pending("005930", "2026-10-06");
        when(records.findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(any())).thenReturn(List.of(past, future));
        scheduler.onApplicationReady();
        verify(records, timeout(2000)).finalizeOutcome(eq(past.getId()), any(), eq("UNKNOWN"), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(candles);
    }
    @Test void backlogUsesExactHistoricalClosesAndStoresThemOnce() {
        var item = pending("005930", "2026-09-28");
        when(candles.getClosingCandles("005930", LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-29")))
            .thenReturn(new KisDailyCandleResult(List.of(bar("2026-09-28",100),bar("2026-09-29",101.2),bar("2026-10-06",120)), false));
        when(ai.generateFeedback(any())).thenReturn(Optional.of(new AiFeedbackResponse("UP 예상과 실제 UP")));
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        var request = org.mockito.ArgumentCaptor.forClass(com.finsight.external.AiFeedbackRequest.class);
        verify(ai).generateFeedback(request.capture());
        assertThat(request.getValue().actualChangePercent()).isCloseTo(1.2, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(request.getValue().comparisonDate()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(request.getValue().relatedSymbolName()).isEqualTo("삼성전자");
        assertThat(request.getValue().judgementClose()).isEqualByComparingTo("100");
        assertThat(item.getActualDirection()).isEqualTo("UP");
        assertThat(item.getActualChangePercent()).isCloseTo(1.2, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(item.getComparisonDate()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(item.getJudgementClose()).isEqualByComparingTo("100");
        assertThat(item.getComparisonClose()).isEqualByComparingTo("101.2");
        assertThat(item.getFeedbackText()).doesNotContain("UP", "UNKNOWN");
        assertThat(scheduler.runNow(1L)).isZero();
        verify(records, times(1)).finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void allDirectionsUseClosesWithFreeTemplateWhenAiIsUnavailable() {
        for (double close : new double[]{101,100.5,99}) {
            var item = pending("005930", "2026-10-02");
            when(candles.getClosingCandles("005930", LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-06")))
                .thenReturn(new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",close)),false));
            assertThat(scheduler.runNow(1L)).isEqualTo(1);
            assertThat(item.getActualDirection()).isEqualTo(close>100.5?"UP":close<99.5?"DOWN":"NEUTRAL");
            assertThat(item.getFeedbackText()).isNotBlank().doesNotContain("UP","DOWN","NEUTRAL");
        }
    }
    @Test void invalidMissingOrFallbackClosesStayPending() {
        for (var result : List.of(new KisDailyCandleResult(List.of(),true),
                new KisDailyCandleResult(List.of(bar("2026-10-02",100)),false),
                new KisDailyCandleResult(List.of(bar("2026-10-02",0),bar("2026-10-06",100)),false),
                new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",Double.NaN)),false))) {
            var item = pending("005930", "2026-10-02");
            when(candles.getClosingCandles(any(), any(), any())).thenReturn(result);
            assertThat(scheduler.runNow(1L)).isZero();
            assertThat(item.getActualDirection()).isNull(); assertThat(item.getJudgementClose()).isNull();
        }
        verify(records,never()).finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()); verifyNoInteractions(ai);
    }
    @Test void noSymbolIsNotAnIncorrectResult() {
        var item = pending(null,"2026-10-02");
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        assertThat(item.getFeedbackText()).isEqualTo("관련 종목이 없어 결과를 비교하지 않았어요");
        assertThat(item.getActualDirection()).isEqualTo("UNKNOWN"); verifyNoInteractions(candles, ai);
    }
    @Test void indexAlsoUsesHistoricalIndexCloses() {
        var item = pending("KOSDAQ","2026-09-28");
        when(candles.getClosingCandles(any(),any(),any())).thenReturn(new KisDailyCandleResult(List.of(bar("2026-09-28",1000),bar("2026-09-29",990)),false));
        assertThat(scheduler.runNow(1L)).isEqualTo(1); assertThat(item.getActualDirection()).isEqualTo("DOWN");
        verify(candles).getClosingCandles("KOSDAQ",LocalDate.parse("2026-09-28"),LocalDate.parse("2026-09-29"));
    }
    @Test void todayAndBeforeComparisonCloseNeverCallProvider() {
        var item = pending("005930","2026-10-06"); assertThat(scheduler.runNow(1L)).isZero();
        item = pending("005930","2026-10-02");
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(Instant.parse("2026-10-06T06:30:00Z"),ZoneId.of("Asia/Seoul")));
        assertThat(scheduler.runNow(1L)).isZero(); verifyNoInteractions(candles,ai);
    }
    @Test void calendarSkipsHolidaysButSeptember28IsOpen() {
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2026-09-23"))).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2026-10-02"))).isEqualTo(LocalDate.parse("2026-10-06"));
        assertThat(KoreanTradingCalendar.onOrBefore(LocalDate.parse("2026-10-05"))).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2026-02-13"))).isEqualTo(LocalDate.parse("2026-02-19"));
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2026-06-02"))).isEqualTo(LocalDate.parse("2026-06-04"));
    }
    @Test void structuredAiReasonsAreSavedInTheSameConditionalUpdate() {
        var item = pending("005930", "2026-10-02");
        when(candles.getClosingCandles(any(), any(), any())).thenReturn(new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",101.2)), false));
        var reasons = List.of(new AiFeedbackResponse.Reason("기사 근거", "기사에 나온 사실이에요."),new AiFeedbackResponse.Reason("실제 시장", "종가는 +1.20% 움직였어요."));
        when(ai.generateFeedback(any())).thenReturn(Optional.of(new AiFeedbackResponse("CORRECT", "정확한 판단이에요!", "삼성전자 종가 기준 +1.20%.", reasons, "같은 기준을 확인해요.")));
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        var content = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(records).finalizeOutcome(any(),any(),any(),any(),eq("삼성전자 종가 기준 +1.20%."),any(),any(),any(),any(),eq("정확한 판단이에요!"),content.capture());
        assertThat(content.getValue().path("reasons").size()).isEqualTo(reasons.size());
        assertThat(content.getValue().path("nextTip").asText()).isEqualTo("같은 기준을 확인해요.");
        assertThat(Judgement.feedbackJson(item.getFeedbackContent())).isEqualTo(content.getValue());
    }
    @Test void noReasonNeverPraisesAndSmallAlignedResultIsPartial() {
        var item = pending("005930", "2026-10-02");
        when(item.getReasonText()).thenReturn("  ");
        when(candles.getClosingCandles(any(),any(),any())).thenReturn(new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",100.7)),false));
        when(ai.generateFeedback(any())).thenReturn(Optional.of(new AiFeedbackResponse("잘 연결한 정확한 판단이에요")));
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        assertThat(item.getFeedbackContent().verdict()).isEqualTo("PARTIAL");
        assertThat(item.getFeedbackContent().reasons()).hasSize(3);
        assertThat(item.getFeedbackHeadline()).doesNotContain("정확한 판단");
        assertThat(item.getFeedbackText()).doesNotContain("잘 연결", "정확한 판단").contains("한 줄 남겨보세요");
    }

}
