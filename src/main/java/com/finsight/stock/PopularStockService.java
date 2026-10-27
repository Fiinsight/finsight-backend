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
    private static final Map<String, String> POPULAR_SYMBOLS = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = Map.of(
            "삼전", "005930",
            "삼성", "005930",
            "SK하닉", "000660",
            "네이버", "035420",
            "포스코홀딩스", "005490"
    );

    static {
        POPULAR_SYMBOLS.put("005930", "삼성전자");
        POPULAR_SYMBOLS.put("000660", "SK하이닉스");
        POPULAR_SYMBOLS.put("035420", "NAVER");
        POPULAR_SYMBOLS.put("035720", "카카오");
        POPULAR_SYMBOLS.put("373220", "LG에너지솔루션");
    }

    private final KisStockQuoteClient kisStockQuoteClient;

    public PopularStockService(KisStockQuoteClient kisStockQuoteClient) {
        this.kisStockQuoteClient = kisStockQuoteClient;
    }

    @Cacheable(cacheNames = "popularStockQuotes", key = "'all'", unless = "#result.?[!fallback()].isEmpty()")
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
        if (normalized.matches("\\d{6}") && !StockCatalog.entries().containsKey(normalized)) return List.of(new StockSearchView(normalized, normalized + " 차트 보기"));
        return StockCatalog.entries().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(normalized)
                        || entry.getValue().name().toLowerCase(Locale.ROOT).contains(normalized)
                        || ALIASES.entrySet().stream().anyMatch(alias -> alias.getValue().equals(entry.getKey())
                                && alias.getKey().toLowerCase(Locale.ROOT).contains(normalized)))
                .sorted(java.util.Comparator.comparingInt(entry -> entry.getKey().equals(normalized) || entry.getValue().name().equalsIgnoreCase(normalized) ? 0 : 1))
                .map(entry -> new StockSearchView(entry.getKey(), entry.getValue().name()))
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
