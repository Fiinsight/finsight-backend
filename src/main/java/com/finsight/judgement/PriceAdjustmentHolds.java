package com.finsight.judgement;

/** Bounded, transient explanation only; ungraded DB rows remain retryable after restart. */
@org.springframework.stereotype.Component
public class PriceAdjustmentHolds {
    private final com.github.benmanes.caffeine.cache.Cache<Long,Boolean> holds =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(10000)
                    .expireAfterWrite(java.time.Duration.ofDays(2)).build();
    public void mark(Long id) { holds.put(id,true); }
    public void clear(Long id) { holds.invalidate(id); }
    public boolean contains(Long id) { return Boolean.TRUE.equals(holds.getIfPresent(id)); }
}
