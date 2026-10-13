package com.finsight.judgement;

import com.finsight.external.kis.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.cache.Cache;

/** Read any existing daily/range cache entry before requesting another KIS range. */
public final class ClosingPrices {
    public record Pair(double before, double after) {
        public double percent() { return (after / before - 1) * 100; }
    }
    private ClosingPrices() {}
    public static Optional<Pair> from(KisDailyCandleResult result, LocalDate start, LocalDate end) {
        if (result == null || result.fallback()) return Optional.empty();
        var before = result.candles().stream().filter(c -> start.equals(c.date())).findFirst();
        var after = result.candles().stream().filter(c -> end.equals(c.date())).findFirst();
        if (before.isEmpty() || after.isEmpty() || !Double.isFinite(before.get().close()) || before.get().close() <= 0
                || !Double.isFinite(after.get().close()) || after.get().close() <= 0) return Optional.empty();
        return Optional.of(new Pair(before.get().close(), after.get().close()));
    }
    public static Optional<Pair> cached(Cache cache, String symbol, LocalDate start, LocalDate end) {
        if (cache == null) return Optional.empty();
        Object raw = cache.getNativeCache();
        Map<?,?> entries = raw instanceof com.github.benmanes.caffeine.cache.Cache<?,?> caffeine ? caffeine.asMap()
                : raw instanceof Map<?,?> map ? map : Map.of();
        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof KisDailyCandleResult data)) continue;
            if (!(key.startsWith(symbol + ":") && key.endsWith(":D") || key.startsWith("close:" + symbol + ":"))) continue;
            var pair = from(data,start,end); if (pair.isPresent()) return pair;
        }
        return Optional.empty();
    }
}
