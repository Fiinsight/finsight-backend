package com.finsight.stock;

import com.finsight.external.kis.KisStockQuote;
import com.finsight.external.kis.KisStockQuoteClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Real current-price quotes for a fixed set of well-known symbols, shown as
 * the "인기 종목" chips on the AI 차트 screen — previously these were a
 * static hardcoded list on the frontend that never changed.
 */
@Service
public class PopularStockService {

    // Keep this local catalog separate from quote fetching so search never calls KIS.
    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    private static final Map<String, String> POPULAR_SYMBOLS = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = Map.of(
            "삼전", "005930",
            "삼성", "005930",
            "SK하닉", "000660",
            "네이버", "035420",
            "포스코홀딩스", "005490"
    );

    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("373220", "LG에너지솔루션");
        POPULAR_SYMBOLS.putAll(SYMBOLS);
        SYMBOLS.put("005380", "현대차");
        SYMBOLS.put("000270", "기아");
        SYMBOLS.put("207940", "삼성바이오로직스");
        SYMBOLS.put("051910", "LG화학");
        SYMBOLS.put("068270", "셀트리온");
        SYMBOLS.put("005490", "POSCO홀딩스");
    }

    private final KisStockQuoteClient kisStockQuoteClient;

    public PopularStockService(KisStockQuoteClient kisStockQuoteClient) {
        this.kisStockQuoteClient = kisStockQuoteClient;
    }

    @Cacheable(cacheNames = "popularStockQuotes", key = "'all'")
    public List<PopularStockView> getPopularStocks() {
        return POPULAR_SYMBOLS.entrySet().stream()
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

    public List<StockSearchView> search(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        return SYMBOLS.entrySet().stream()
                .filter(entry -> entry.getKey().contains(normalized)
                        || entry.getValue().toLowerCase(Locale.ROOT).contains(normalized)
                        || ALIASES.entrySet().stream().anyMatch(alias -> alias.getValue().equals(entry.getKey())
                                && alias.getKey().toLowerCase(Locale.ROOT).contains(normalized)))
                .map(entry -> new StockSearchView(entry.getKey(), entry.getValue()))
                .limit(10)
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
