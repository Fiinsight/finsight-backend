package com.finsight.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Reuses rendered JSON only while the source snapshot, level and live quote agree. */
@Component
public final class ChartInsightsJson {
    private final ChartService charts;
    private final ObjectMapper mapper;
    private final Cache<Key, byte[]> encoded = Caffeine.newBuilder()
            .maximumSize(16).expireAfterWrite(Duration.ofMinutes(1)).build();

    public ChartInsightsJson(ChartService charts, ObjectMapper mapper) {
        this.charts = charts;
        this.mapper = mapper;
    }

    public ResponseEntity<byte[]> response(ChartResponse source, String level) {
        var current = charts.withCurrentQuote(source);
        byte[] body = source.fallback() ? render(current, level)
                : encoded.get(new Key(source, level, Double.doubleToLongBits(current.price()),
                        Double.doubleToLongBits(current.changePercent())), ignored -> render(current, level));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private byte[] render(ChartResponse current, String level) {
        try {
            return mapper.writeValueAsBytes(charts.withDocents(current, level));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new UncheckedIOException("Could not encode chart insights", error);
        }
    }

    // Cached snapshots use immutable lists. Identity avoids hashing nested articles and terms.
    private record Key(ChartResponse source, String level, long price, long change) {
        @Override public int hashCode() {
            int hash = 31 * System.identityHashCode(source) + level.hashCode();
            return 31 * (31 * hash + Long.hashCode(price)) + Long.hashCode(change);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && source == key.source && level.equals(key.level)
                    && price == key.price && change == key.change;
        }
    }
}
