package com.finsight.chart;

import com.finsight.external.kis.*;
import com.finsight.judgement.ClosingPrices;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChartIndexHistoryTest {
    private static KisDailyCandle candle(String day,double close) {
        return new KisDailyCandle(LocalDate.parse(day),close,close,close,close);
    }
    @Test void oneIndexHistoryRequestIsSharedAcrossStockMovesAndMatchesFeedbackCalculation() {
        var client=mock(KisDailyCandleClient.class);
        var data=new KisDailyCandleResult(List.of(candle("2026-11-02",100),candle("2026-11-03",97)),false);
        when(client.getCandlesWithStatus("KOSPI",100,"D")).thenReturn(data);
        var history=new ChartIndexHistory(client,Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"),ZoneId.of("Asia/Seoul")));
        for(int stock=0;stock<10;stock++) for(int move=0;move<5;move++) {
            var result=history.get("KOSPI","D");
            assertThat(ChartIndexHistory.change(result,LocalDate.parse("2026-11-03"),"D"))
                    .isEqualTo(ClosingPrices.from(data,LocalDate.parse("2026-11-02"),LocalDate.parse("2026-11-03")).orElseThrow().percent());
        }
        verify(client,times(1)).getCandlesWithStatus("KOSPI",100,"D");
    }
    @Test void fallbackMissingSessionsAndIncompleteWeekNeverBecomeZeroPercent() {
        var client=mock(KisDailyCandleClient.class);
        when(client.getCandlesWithStatus("KOSPI",26,"W")).thenReturn(new KisDailyCandleResult(List.of(candle("2026-10-26",100),candle("2026-11-02",95),candle("2026-11-09",90)),false));
        var history=new ChartIndexHistory(client,Clock.fixed(Instant.parse("2026-11-12T02:00:00Z"),ZoneId.of("Asia/Seoul")));
        assertThat(ChartIndexHistory.change(history.get("KOSPI","W"),LocalDate.parse("2026-11-02"),"W")).isCloseTo(-5.0,within(1e-9));
        assertThat(ChartIndexHistory.change(history.get("KOSPI","W"),LocalDate.parse("2026-11-09"),"W")).isNull();
        assertThat(ChartIndexHistory.change(new KisDailyCandleResult(List.of(candle("2026-11-02",100),candle("2026-11-04",95)),false),LocalDate.parse("2026-11-04"),"D")).isNull();
        assertThat(ChartIndexHistory.change(new KisDailyCandleResult(List.of(),true),LocalDate.parse("2026-11-04"),"D")).isNull();
    }
}
