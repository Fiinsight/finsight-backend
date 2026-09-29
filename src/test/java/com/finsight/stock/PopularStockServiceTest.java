package com.finsight.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsight.external.kis.KisStockQuote;
import com.finsight.external.kis.KisStockQuoteClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PopularStockServiceTest {

    @Mock
    private KisStockQuoteClient quoteClient;

    @Test
    void cachesTheFivePopularStockCalls() {
        when(quoteClient.getStockQuote(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new KisStockQuote(invocation.getArgument(0), 70_000, 0, true));

        PopularStockService service = new PopularStockService(quoteClient);
        assertThat(service.getPopularStocks()).hasSize(5);
        assertThat(service.getPopularStocks()).hasSize(5);

        verify(quoteClient, times(1)).getStockQuote("005930");
        verify(quoteClient, times(1)).getStockQuote("000660");
        verify(quoteClient, times(1)).getStockQuote("035420");
        verify(quoteClient, times(1)).getStockQuote("035720");
        verify(quoteClient, times(1)).getStockQuote("373220");
    }
}
