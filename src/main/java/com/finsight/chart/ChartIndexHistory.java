package com.finsight.chart;

import com.finsight.external.kis.*;
import com.finsight.judgement.ClosingPrices;
import com.finsight.judgement.KoreanTradingCalendar;
import java.time.*;
import java.util.*;

/** Shared across stocks: complete index sessions only, at most eight small cached series. */
final class ChartIndexHistory {
    private final KisDailyCandleClient client;
    private final com.github.benmanes.caffeine.cache.Cache<String, KisDailyCandleResult> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(8)
                    .expireAfterWrite(Duration.ofHours(6)).build();
    private final Clock clock;
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> failures =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(8)
                    .expireAfterWrite(Duration.ofSeconds(30)).build();
    ChartIndexHistory(KisDailyCandleClient client) { this(client, Clock.system(ZoneId.of("Asia/Seoul"))); }
    ChartIndexHistory(KisDailyCandleClient client, Clock clock) { this.client=client; this.clock=clock; }

    KisDailyCandleResult get(String symbol, String period) {
        if (client==null || !("D".equals(period) || "W".equals(period))) return null;
        var now=LocalDateTime.now(clock);
        var complete=KoreanTradingCalendar.onOrBefore(now.toLocalTime().isBefore(LocalTime.of(15,40))
                ? now.toLocalDate().minusDays(1) : now.toLocalDate());
        String key=symbol+":"+period+":"+complete;
        if (failures.getIfPresent(key)!=null) return null;
        var result=cache.get(key, ignored -> {
            var raw=client.getCandlesWithStatus(symbol,"W".equals(period)?26:100,period);
            if (raw==null || raw.fallback()) { failures.put(key,true); return null; }
            var rows=raw.candles().stream().filter(c -> {
                var end="W".equals(period) ? KoreanTradingCalendar.onOrBefore(c.date()
                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(4)) : c.date();
                return !end.isAfter(complete);
            }).toList();
            return rows.isEmpty()?null:new KisDailyCandleResult(rows,false);
        });
        return result;
    }

    static Double change(KisDailyCandleResult data, LocalDate day, String period) {
        if (data==null || data.fallback()) return null;
        var rows=data.candles().stream().sorted(Comparator.comparing(KisDailyCandle::date)).toList();
        for (int i=1;i<rows.size();i++) {
            var at=rows.get(i).date();
            boolean same="W".equals(period) ? at.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .equals(day.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) : at.equals(day);
            if (!same) continue;
            var before=rows.get(i-1).date();
            if ("D".equals(period) && !before.equals(KoreanTradingCalendar.previous(day))) return null;
            return ClosingPrices.from(data,before,at).map(ClosingPrices.Pair::percent).orElse(null);
        }
        return null;
    }
}
