package com.finsight.market;

import com.finsight.external.EcosClient;
import com.finsight.external.EcosRate;
import com.finsight.external.NaverFxClient;
import com.finsight.external.kis.KisIndexQuote;
import com.finsight.external.kis.KisIndexQuoteClient;
import com.finsight.market.MarketSummaryResponse.MarketIndexView;
import com.finsight.market.MarketSummaryResponse.RateView;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;

@Service
public class MarketSummaryService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private static final String KOSPI_INDEX_CODE = "0001";
    private static final String KOSDAQ_INDEX_CODE = "1001";

    private final KisIndexQuoteClient kisIndexQuoteClient;
    private final EcosClient ecosClient;
    private final NaverFxClient naverFxClient;
    private volatile CachedSummary cachedSummary;

    public MarketSummaryService(KisIndexQuoteClient kisIndexQuoteClient, EcosClient ecosClient, NaverFxClient naverFxClient) {
        this.kisIndexQuoteClient = kisIndexQuoteClient;
        this.ecosClient = ecosClient;
        this.naverFxClient = naverFxClient;
    }

    public MarketSummaryResponse getSummary() {
        CachedSummary current = cachedSummary;
        if (current != null && current.expiresAt().isAfter(Instant.now())) {
            return current.value();
        }

        return refreshSummaryIfNeeded();
    }

    private synchronized MarketSummaryResponse refreshSummaryIfNeeded() {
        CachedSummary current = cachedSummary;
        if (current != null && current.expiresAt().isAfter(Instant.now())) {
            return current.value();
        }

        KisIndexQuote kospi = kisIndexQuoteClient.getIndexQuote(KOSPI_INDEX_CODE);
        // KIS's paper-trading tier limits requests per second. Calls are kept
        // sequential, but the result is cached so user requests do not pay a
        // blocking sleep or repeat the same external calls.
        KisIndexQuote kosdaq = kisIndexQuoteClient.getIndexQuote(KOSDAQ_INDEX_CODE);
        EcosRate baseRate = ecosClient.getBaseRate();
        // ECOS's daily 매매기준율 is fixed once each morning — 원/달러 now comes from
        // Naver's live 고시환율 feed instead, which republishes many times a day.
        EcosRate usdKrwRate = naverFxClient.getUsdKrwRate();

        MarketSummaryResponse result = new MarketSummaryResponse(
                new MarketIndexView(kospi.currentValue(), kospi.changePercent(), kospi.fallback()),
                new MarketIndexView(kosdaq.currentValue(), kosdaq.changePercent(), kosdaq.fallback()),
                new RateView(baseRate.value(), baseRate.asOfPeriod(), baseRate.changePercent(), baseRate.fallback()),
                new RateView(usdKrwRate.value(), usdKrwRate.asOfPeriod(), usdKrwRate.changePercent(), usdKrwRate.fallback())
        );
        cachedSummary = new CachedSummary(result, Instant.now().plus(CACHE_TTL));
        return result;
    }

    private record CachedSummary(MarketSummaryResponse value, Instant expiresAt) { }
}
