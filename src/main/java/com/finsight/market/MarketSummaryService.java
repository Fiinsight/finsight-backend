package com.finsight.market;

import com.finsight.external.EcosClient;
import com.finsight.external.EcosRate;
import com.finsight.external.NaverFxClient;
import com.finsight.external.kis.KisIndexQuote;
import com.finsight.external.kis.KisIndexQuoteClient;
import com.finsight.market.MarketSummaryResponse.MarketIndexView;
import com.finsight.market.MarketSummaryResponse.RateView;
import org.springframework.stereotype.Service;

@Service
public class MarketSummaryService {

    private static final String KOSPI_INDEX_CODE = "0001";
    private static final String KOSDAQ_INDEX_CODE = "1001";

    private final KisIndexQuoteClient kisIndexQuoteClient;
    private final EcosClient ecosClient;
    private final NaverFxClient naverFxClient;

    public MarketSummaryService(KisIndexQuoteClient kisIndexQuoteClient, EcosClient ecosClient, NaverFxClient naverFxClient) {
        this.kisIndexQuoteClient = kisIndexQuoteClient;
        this.ecosClient = ecosClient;
        this.naverFxClient = naverFxClient;
    }

    public MarketSummaryResponse getSummary() {
        KisIndexQuote kospi = kisIndexQuoteClient.getIndexQuote(KOSPI_INDEX_CODE);
        // KIS's 모의투자 (paper trading) tier enforces a strict per-second call
        // limit — firing the KOSDAQ call immediately after KOSPI reliably hit
        // "EGW00201 초당 거래건수를 초과하였습니다" and silently fell back.
        sleepBetweenKisCalls();
        KisIndexQuote kosdaq = kisIndexQuoteClient.getIndexQuote(KOSDAQ_INDEX_CODE);
        EcosRate baseRate = ecosClient.getBaseRate();
        // ECOS's daily 매매기준율 is fixed once each morning — 원/달러 now comes from
        // Naver's live 고시환율 feed instead, which republishes many times a day.
        EcosRate usdKrwRate = naverFxClient.getUsdKrwRate();

        return new MarketSummaryResponse(
                new MarketIndexView(kospi.currentValue(), kospi.changePercent(), kospi.fallback()),
                new MarketIndexView(kosdaq.currentValue(), kosdaq.changePercent(), kosdaq.fallback()),
                new RateView(baseRate.value(), baseRate.asOfPeriod(), baseRate.changePercent(), baseRate.fallback()),
                new RateView(usdKrwRate.value(), usdKrwRate.asOfPeriod(), usdKrwRate.changePercent(), usdKrwRate.fallback())
        );
    }

    private void sleepBetweenKisCalls() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
