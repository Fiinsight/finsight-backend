package com.finsight.stock;

import com.finsight.external.kis.KisStockQuote;
import com.finsight.external.kis.KisStockQuoteClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Real current-price quotes for a fixed set of well-known symbols, shown as
 * the "인기 종목" chips on the AI 차트 screen — previously these were a
 * static hardcoded list on the frontend that never changed.
 */
@Service
public class PopularStockService {

    // Same 5 symbols the frontend previously hardcoded as sample data.
    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();

    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("373220", "LG에너지솔루션");
    }

    private final KisStockQuoteClient kisStockQuoteClient;

    public PopularStockService(KisStockQuoteClient kisStockQuoteClient) {
        this.kisStockQuoteClient = kisStockQuoteClient;
    }

    public List<PopularStockView> getPopularStocks() {
        return SYMBOLS.entrySet().stream()
                .map(entry -> {
                    KisStockQuote quote = kisStockQuoteClient.getStockQuote(entry.getKey());
                    // KIS's 모의투자 tier throttles hard on back-to-back calls (see
                    // MarketSummaryService) — 5 sequential quote calls need the same
                    // spacing or every call after the first silently falls back.
                    sleepBetweenKisCalls();
                    return new PopularStockView(entry.getKey(), entry.getValue(), quote.currentPrice(), quote.changePercent(), quote.fallback());
                })
                .toList();
    }

    private void sleepBetweenKisCalls() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
