package com.finsight.judgement;

import com.finsight.auth.User;
import com.finsight.external.AiServiceClient;
import com.finsight.external.kis.*;
import com.finsight.news.News;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnknownRegradeTest {
    private final JudgementRepository records = mock(JudgementRepository.class);
    private final KisDailyCandleClient candles = mock(KisDailyCandleClient.class);
    private final AiServiceClient ai = mock(AiServiceClient.class);
    private final FeedbackScheduler scheduler = new FeedbackScheduler(records,candles,ai);
    private final Instant now = Instant.parse("2026-10-14T08:00:00Z");

    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(now,ZoneId.of("Asia/Seoul")));
        when(candles.getClosingCandles(eq("KOSPI"),any(),any())).thenAnswer(call -> bars(call.getArgument(1),call.getArgument(2),1000,1010));
        when(records.regradeUnknownOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
    }
    private KisDailyCandleResult bars(LocalDate start,LocalDate end,double before,double after) {
        return new KisDailyCandleResult(List.of(new KisDailyCandle(start,before,before,before,before),new KisDailyCandle(end,after,after,after,after)),false);
    }
    private Judgement row(long id,String title,String date) {
        var owner=mock(User.class);when(owner.getId()).thenReturn(7L);
        var news=new News(title,"https://example.test/"+id,"fixture",now,title+". 기업의 매출을 확인했다.");
        var row=spy(new Judgement(owner,news,JudgementChoice.UP,"매출 증가를 기대해요."));
        when(row.getId()).thenReturn(id);when(row.getCreatedAt()).thenReturn(Instant.parse(date+"T01:00:00Z"));
        row.setActualDirection("UNKNOWN");row.setFeedbackText("결과를 비교할 수 없어요");row.setFeedbackGeneratedAt(Instant.parse("2026-10-01T08:00:00Z"));
        return row;
    }
    private void selected(Judgement... rows) { when(records.findUnknownByOwner(7L)).thenReturn(List.of(rows)); }
    private void noWrites() {
        verify(records,never()).regradeUnknownOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(records,never()).save(any());
        verify(records,never()).finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @ParameterizedTest @CsvSource({"2026-09-28,2026-09-29","2026-09-29,2026-09-30","2026-09-30,2026-10-01"})
    void dryRunUsesExactOldTradingDatesAndNeverChangesEntityOrDatabase(String before,String after) {
        var row=row(3,"기업 지원 정책 안내",before);selected(row);
        var response=scheduler.regradeUnknown(7L,false);
        assertThat(response.dryRun()).isTrue();assertThat(response.ready()).isEqualTo(1);assertThat(response.applied()).isZero();
        var value=response.records().getFirst();assertThat(value.status()).isEqualTo("READY");assertThat(value.targetSymbol()).isEqualTo("KOSPI");
        assertThat(value.judgementClose()).isEqualTo(1000);assertThat(value.comparisonClose()).isEqualTo(1010);
        assertThat(value.actualDirection()).isEqualTo("UP");assertThat(value.actualChangePercent()).isCloseTo(1.,offset(.000001));
        assertThat(value.indexSymbol()).isNull();
        verify(candles).getClosingCandles("KOSPI",LocalDate.parse(before),LocalDate.parse(after));
        noWrites();verifyNoInteractions(ai);
        assertThat(row.getActualDirection()).isEqualTo("UNKNOWN");assertThat(row.getComparisonDate()).isNull();assertThat(row.getTargetSymbol()).isNull();
        assertThat(row.getFeedbackText()).isEqualTo("결과를 비교할 수 없어요");
        assertThat(row.getFeedbackGeneratedAt()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }
    @Test void applyGetsStockAndBenchmarkClosesAndRefreshesFeedbackReceipt() {
        var row=row(5,"포스코인터 매출 증가","2026-09-28");selected(row);
        when(candles.getClosingCandles(eq("047050"),any(),any())).thenAnswer(call -> bars(call.getArgument(1),call.getArgument(2),100,102));
        when(ai.generateFeedback(any())).thenAnswer(call -> Optional.of(JudgementFeedbackRules.generate(call.getArgument(0))));
        var result=scheduler.regradeUnknown(7L,true);
        assertThat(result.applied()).isEqualTo(1);assertThat(result.records().getFirst().status()).isEqualTo("APPLIED");
        assertThat(result.records().getFirst().indexSymbol()).isEqualTo("KOSPI");
        assertThat(result.records().getFirst().indexChangePercent()).isCloseTo(1.,offset(.000001));
        verify(ai).generateFeedback(argThat(request -> "047050".equals(request.relatedSymbol()) && "코스피".equals(request.indexName()) && request.comparisonDate().equals(LocalDate.parse("2026-09-29"))));
        var judgedAt=row.getCreatedAt();
        verify(records).regradeUnknownOutcome(eq(5L),eq(7L),eq(judgedAt),eq("047050"),eq("UP"),any(),eq(LocalDate.parse("2026-09-29")),
                eq(java.math.BigDecimal.valueOf(100.)),eq(java.math.BigDecimal.valueOf(102.)),contains("+2.00%"),any(),
                argThat(content -> content.path("reasons").size()==3 && content.path("indexName").asText().equals("코스피")),eq(now));
    }
    @Test void applyOnlyWritesReadyRowsAndContinuesAfterMissingPrices() {
        var ready=row(3,"기업 지원 정책 안내","2026-09-28");var absent=row(6,"한미반도체 매출 증가","2026-09-30");selected(ready,absent);
        when(candles.getClosingCandles(eq("042700"),any(),any())).thenReturn(new KisDailyCandleResult(List.of(),true));
        var result=scheduler.regradeUnknown(7L,true);
        assertThat(result.applied()).isEqualTo(1);assertThat(result.records().get(1).status()).isEqualTo("NO_TARGET_CLOSES");
        assertThat(result.records().get(1).reason()).contains("종가");
        verify(records,times(1)).regradeUnknownOutcome(eq(3L),eq(7L),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),eq(now));
        verify(ai,times(1)).generateFeedback(any());
    }
    @Test void alreadyGradedComparedAndLocalQaRowsAreSkippedBeforeAnyProviderCall() {
        var graded=row(1,"기업 소식","2026-09-28");graded.setActualDirection("UP");
        var compared=row(2,"기업 소식","2026-09-28");compared.setClosingComparison(LocalDate.parse("2026-09-29"),100,101);
        selected(graded,compared,row(19,"로컬 QA 기록 화면 검증","2026-09-28"));
        assertThat(scheduler.regradeUnknown(7L,true).records()).allMatch(value -> value.status().equals("SKIPPED"));
        noWrites();verifyNoInteractions(candles,ai);
    }
    @Test void missingIndexSkipsTheStockWithoutInventingAMarketComparison() {
        selected(row(5,"포스코인터 매출 증가","2026-09-28"));
        when(candles.getClosingCandles(eq("047050"),any(),any())).thenAnswer(call -> bars(call.getArgument(1),call.getArgument(2),100,102));
        when(candles.getClosingCandles(eq("KOSPI"),any(),any())).thenReturn(new KisDailyCandleResult(List.of(),false));
        var value=scheduler.regradeUnknown(7L,true).records().getFirst();
        assertThat(value.status()).isEqualTo("NO_INDEX_CLOSES");assertThat(value.reason()).contains("시장 지수");
        assertThat(value.indexChangePercent()).isNull();noWrites();verifyNoInteractions(ai);
    }
    @Test void providerExceptionIsReportedAndDoesNotStopOtherRows() {
        selected(row(6,"한미반도체 매출 증가","2026-09-28"),row(3,"기업 지원 정책 안내","2026-09-28"));
        when(candles.getClosingCandles(eq("042700"),any(),any())).thenThrow(new IllegalStateException("provider details"));
        var response=scheduler.regradeUnknown(7L,false);
        assertThat(response.records().getFirst().status()).isEqualTo("FAILED");
        assertThat(response.records().getFirst().reason()).contains("실패").doesNotContain("provider details");
        assertThat(response.records().get(1).status()).isEqualTo("READY");noWrites();
    }
    @Test void aiServerFailureFallsBackToRulesAndConcurrentApplyDoesNotOverwrite() {
        selected(row(3,"기업 지원 정책 안내","2026-09-28"));
        when(ai.generateFeedback(any())).thenThrow(new IllegalStateException("AI unavailable"));
        assertThat(scheduler.regradeUnknown(7L,true).records().getFirst().status()).isEqualTo("APPLIED");
        when(records.regradeUnknownOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
        var repeated=scheduler.regradeUnknown(7L,true);
        assertThat(repeated.applied()).isZero();assertThat(repeated.records().getFirst().status()).isEqualTo("CONFLICT");
    }
    @Test void noTargetAndFutureDatesDoNotCallProviders() {
        selected(row(3,"[부고] 기업 임원 별세","2026-09-28"),row(4,"기업 정책 안내","2026-10-14"));
        assertThat(scheduler.regradeUnknown(7L,true).records()).extracting(JudgementRegradeResponse.Row::status).containsExactly("NO_TARGET","NOT_DUE");
        noWrites();verifyNoInteractions(candles,ai);
    }
}
