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
        when(records.finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        when(candles.getClosingCandles(eq("KOSPI"),any(),any())).thenAnswer(inv -> new KisDailyCandleResult(List.of(bar(inv.getArgument(1).toString(),100),bar(inv.getArgument(2).toString(),100.2)),false));
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "clock", Clock.fixed(Instant.parse("2026-10-06T07:00:00Z"), ZoneId.of("Asia/Seoul")));
    }
    private Judgement pending(String symbol, String date) {
        var news = spy(new News("학습용 기사", "https://example.invalid/qa", "SYNTHETIC_QA", Instant.now(), "QA"));
        when(news.getId()).thenReturn(115L); news.setRelatedSymbol(symbol);
        var item = spy(new Judgement(mock(User.class), news, JudgementChoice.UP, "근거"));
        item.setTargetSymbol(symbol);
        when(item.getCreatedAt()).thenReturn(Instant.parse(date + "T01:00:00Z"));
        when(records.findPendingByOwner(eq(1L), any())).thenReturn(List.of(item));
        return item;
    }
    private KisDailyCandle bar(String date, double close) { return new KisDailyCandle(LocalDate.parse(date), close, close, close, close); }
    @Test void startupFinalizesUnresolvablePastButSkipsFutureComparison() {
        var past = pending(null, "2026-09-28");
        var future = pending("005930", "2026-10-06");
        when(records.findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(any())).thenReturn(List.of(past, future));
        scheduler.generatePendingFeedback();
        assertThat(past.getActualDirection()).isEqualTo("UNKNOWN");
        assertThat(future.getActualDirection()).isNull();
        verify(records).finalizeOutcome(any(),any(),eq("UNKNOWN"),isNull(),eq("판단 대상이 정해지지 않은 기사예요"),any(),isNull(),isNull(),isNull(),any(),any(),isNull());
        verifyNoInteractions(candles,ai);
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
        verify(records, times(1)).finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
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
        verify(records,never()).finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()); verifyNoInteractions(ai);
    }
    @Test void unresolvableDueRecordFinishesUnknownWithoutCallingProviders() {
        var item = pending(null,"2026-10-02");
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        assertThat(item.getFeedbackText()).isEqualTo("판단 대상이 정해지지 않은 기사예요");
        assertThat(item.getActualDirection()).isEqualTo("UNKNOWN");
        assertThat(item.getFeedbackGeneratedAt()).isNotNull();
        assertThat(item.getActualChangePercent()).isNull();
        assertThat(scheduler.runNow(1L)).isZero(); verifyNoInteractions(candles, ai);
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
        when(ai.generateFeedback(any())).thenAnswer(inv -> Optional.of(JudgementFeedbackRules.generate(inv.getArgument(0))));
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        var content = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(records).finalizeOutcome(any(),any(),any(),any(),contains("삼성전자 2026-10-06 종가 기준 +1.20%."),any(),any(),any(),any(),eq("예측 방향이 같았어요"),content.capture(),eq("005930"));
        assertThat(content.getValue().path("reasons").size()).isEqualTo(3);
        assertThat(content.getValue().path("reasons").get(1).path("detail").asText()).contains("운이 섞였을 수");
        assertThat(content.getValue().path("indexName").asText()).isEqualTo("코스피");
        assertThat(content.getValue().path("nextTip").asText()).isNotBlank();
        assertThat(Judgement.feedbackJson(item.getFeedbackContent())).isEqualTo(content.getValue());
    }
    @Test void existingRangeCachesAvoidAllNewKisCalls() {
        var item = pending("005930","2026-10-02");
        var cache = new org.springframework.cache.concurrent.ConcurrentMapCache("kisDailyCandles");
        cache.put("005930:120:D",new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",96)),false));
        cache.put("KOSPI:120:D",new KisDailyCandleResult(List.of(bar("2026-10-02",1000),bar("2026-10-06",963)),false));
        var manager = mock(org.springframework.cache.CacheManager.class);when(manager.getCache("kisDailyCandles")).thenReturn(cache);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler,"cacheManager",manager);
        assertThat(scheduler.runNow(1L)).isEqualTo(1);verifyNoInteractions(candles);
        assertThat(item.getFeedbackContent().indexChangePercent()).isCloseTo(-3.7,org.assertj.core.data.Offset.offset(.000001));
    }
    @Test void missingBenchmarkKeepsStockJudgementPending() {
        pending("005930","2026-10-02");
        when(candles.getClosingCandles(eq("005930"),any(),any())).thenReturn(new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",96)),false));
        when(candles.getClosingCandles(eq("KOSPI"),any(),any())).thenReturn(new KisDailyCandleResult(List.of(),true));
        assertThat(scheduler.runNow(1L)).isZero();verifyNoInteractions(ai);
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

    @Test void missingTargetUsesNewsResolverAndPinsItInTheAtomicOutcome() {
        var item = pending(null,"2026-10-02");
        when(item.getNews().getTitle()).thenReturn("삼성전자 매출 증가");
        when(item.getNews().getRawContent()).thenReturn("삼성전자의 매출이 증가했다.");
        item.getNews().setRelatedSymbol("005930");
        when(candles.getClosingCandles(eq("005930"),any(),any())).thenReturn(new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",102)),false));
        assertThat(scheduler.runNow(1L)).isEqualTo(1);
        assertThat(item.getTargetSymbol()).isEqualTo("005930");
        verify(records).finalizeOutcome(any(),any(),eq("UP"),any(),any(),any(),any(),any(),any(),any(),any(),eq("005930"));
        verify(ai).generateFeedback(argThat(request -> "005930".equals(request.relatedSymbol())));
    }
    @Test void resolvedTargetCannotOverwriteAnotherCompletedOrRevisedOutcome() {
        var item = pending("005930","2026-10-02");
        when(candles.getClosingCandles(eq("005930"),any(),any())).thenReturn(new KisDailyCandleResult(List.of(bar("2026-10-02",100),bar("2026-10-06",102)),false));
        when(records.finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
        assertThat(scheduler.runNow(1L)).isZero();
        assertThat(item.getActualDirection()).isNull();
        assertThat(item.getFeedbackGeneratedAt()).isNull();
    }
    @Test void localQaIsNeverResolvedFinalizedOrSentToProviders() {
        var item = pending(null,"2026-10-02");
        when(item.getNews().getTitle()).thenReturn("로컬 QA 기록 화면 검증 삼성전자");
        assertThat(scheduler.runNow(1L)).isZero();
        verifyNoInteractions(candles,ai);
        verify(records,never()).finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

}
