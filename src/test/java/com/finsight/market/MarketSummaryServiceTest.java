package com.finsight.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsight.external.EcosClient;
import com.finsight.external.EcosRate;
import com.finsight.external.NaverFxClient;
import com.finsight.external.kis.KisIndexQuote;
import com.finsight.external.kis.KisIndexQuoteClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketSummaryServiceTest {

    @Mock
    private KisIndexQuoteClient kisIndexQuoteClient;

    @Mock
    private EcosClient ecosClient;

    @Mock
    private NaverFxClient naverFxClient;

    @InjectMocks
    private MarketSummaryService service;

    @Test
    void cachesExternalMarketCallsForShortPeriod() {
        when(kisIndexQuoteClient.getIndexQuote("0001"))
                .thenReturn(new KisIndexQuote("0001", 2_650.0, 0.5, false));
        when(kisIndexQuoteClient.getIndexQuote("1001"))
                .thenReturn(new KisIndexQuote("1001", 850.0, -0.2, false));
        when(ecosClient.getBaseRate())
                .thenReturn(new EcosRate("기준금리", 3.25, "2026-09", 0.0, false));
        when(naverFxClient.getUsdKrwRate())
                .thenReturn(new EcosRate("원/달러", 1_350.0, "2026-09-22", 0.1, false));

        MarketSummaryResponse first = service.getSummary();
        MarketSummaryResponse second = service.getSummary();

        assertThat(second).isSameAs(first);
        verify(kisIndexQuoteClient, times(1)).getIndexQuote("0001");
        verify(kisIndexQuoteClient, times(1)).getIndexQuote("1001");
        verify(ecosClient, times(1)).getBaseRate();
        verify(naverFxClient, times(1)).getUsdKrwRate();
    }
}
