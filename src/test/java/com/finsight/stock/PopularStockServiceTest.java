package com.finsight.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.finsight.external.kis.KisStockQuoteClient;
import org.junit.jupiter.api.Test;

class PopularStockServiceTest {

    @Test
    void searchesByCompanyNameCodeAndAliasWithoutCallingKis() {
        PopularStockService service = new PopularStockService((KisStockQuoteClient) null);

        assertEquals("005930", service.search("삼성").get(0).symbol());
        assertEquals("005930", service.search("005930").get(0).symbol());
        assertEquals("035420", service.search("네이버").get(0).symbol());
    }
}
