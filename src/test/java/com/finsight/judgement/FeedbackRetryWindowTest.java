package com.finsight.judgement;
import com.finsight.news.News;
import com.finsight.auth.User;
import com.finsight.external.*;
import com.finsight.external.kis.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class FeedbackRetryWindowTest {
    @Test void scheduledQueryUsesCompletedTradingDayAndBoundedPage() {
        var records=mock(JudgementRepository.class);
        var scheduler=new FeedbackScheduler(records,mock(KisDailyCandleClient.class),mock(AiServiceClient.class));
        ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(Instant.parse("2026-11-09T01:00:00Z"),ZoneId.of("Asia/Seoul")));
        scheduler.generatePendingFeedback();
        verify(records).findDueAfterId(eq(Instant.parse("2026-11-05T15:00:00Z")),eq(0L),argThat(p -> p.getPageSize()==100));
        verify(records,never()).findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(any());
    }

    @Test void missingClosesBackOffAndEventuallyStopWithoutInventingAnOutcome() {
        var records=mock(JudgementRepository.class);var candles=mock(KisDailyCandleClient.class);
        var ai=mock(AiServiceClient.class);var scheduler=new FeedbackScheduler(records,candles,ai);
        News n=new News("기업 실적 발표","https://example.invalid/test","TEST",Instant.now(),"실적");
        var row=spy(new Judgement(mock(User.class),n,JudgementChoice.UP,"실적"));
        when(row.getId()).thenReturn(5L);when(row.getCreatedAt()).thenReturn(Instant.parse("2026-11-02T01:00:00Z"));row.setTargetSymbol("005930");
        when(records.findPendingByOwner(eq(1L),any())).thenReturn(List.of(row));
        when(candles.getClosingCandles(any(),any(),any())).thenReturn(new KisDailyCandleResult(List.of(),false));
        when(records.finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(Instant.parse("2026-11-03T07:00:00Z"),ZoneId.of("Asia/Seoul")));
        scheduler.runNow(1L);scheduler.runNow(1L);scheduler.runNow(1L);
        verify(candles,times(1)).getClosingCandles(any(),any(),any());
        ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"),ZoneId.of("Asia/Seoul")));
        assertThat(scheduler.runNow(1L)).isEqualTo(1);assertThat(row.getActualDirection()).isEqualTo("UNKNOWN");
        scheduler.runNow(1L);verify(candles,times(1)).getClosingCandles(any(),any(),any());verifyNoInteractions(ai);
    }
}
