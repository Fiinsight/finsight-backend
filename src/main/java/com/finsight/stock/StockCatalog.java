package com.finsight.stock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bundled KOSPI/KOSDAQ market-cap snapshot; never fetches listings at runtime. */
@Component
public class StockCatalog {
    public record Stock(String code, String name, String market) {}
    private static final Map<String, Stock> STOCKS = load();
    public static Map<String, Stock> entries() { return STOCKS; }
    public static String name(String code) { var stock = STOCKS.get(code); return stock == null ? code : stock.name(); }
    public static String market(String code) { var stock = STOCKS.get(code); return stock == null ? null : stock.market(); }
    public static java.util.List<String> aliases(String code) {
        return switch (code) {
            case "005930" -> java.util.List.of("삼성전자", "삼전");
            case "000660" -> java.util.List.of("SK하이닉스", "하이닉스", "닉스", "하닉");
            default -> java.util.List.of(name(code));
        };
    }
    private static Map<String, Stock> load() {
        var result = new LinkedHashMap<String, Stock>();
        try (var reader = new BufferedReader(new InputStreamReader(StockCatalog.class.getResourceAsStream("/stocks/krx-listed.csv"), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                var columns = line.split(",", -1);
                if (columns.length != 3 || !columns[0].matches("\\d{6}") || columns[1].isBlank()
                        || !(columns[2].equals("KOSPI") || columns[2].equals("KOSDAQ"))) throw new IllegalStateException("Invalid stock catalog row");
                result.put(columns[0], new Stock(columns[0], columns[1], columns[2]));
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot load stock catalog", error); }
        return java.util.Collections.unmodifiableMap(result);
    }
}
