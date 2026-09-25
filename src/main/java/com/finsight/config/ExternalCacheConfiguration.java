package com.finsight.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Small bounded local TTL caches; no managed Redis/cache service is required. */
@Configuration
@EnableCaching
public class ExternalCacheConfiguration {

    @Bean
    CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache("kisMinuteCandles", Duration.ofSeconds(10), 500),
                cache("kisStockQuotes", Duration.ofSeconds(10), 500),
                cache("kisIndexQuotes", Duration.ofSeconds(30), 50),
                cache("naverFx", Duration.ofMinutes(5), 5),
                cache("ecosBaseRate", Duration.ofHours(12), 5)
        ));
        return manager;
    }

    private CaffeineCache cache(String name, Duration ttl, long maximumSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build());
    }
}
