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
            case "005930" -> java.util.List.of("삼성전자", "삼전", "Samsung Electronics");
            case "000660" -> java.util.List.of("SK하이닉스", "하이닉스", "닉스", "하닉", "SK hynix");
            case "047050" -> java.util.List.of("포스코인터내셔널", "포스코인터", "POSCO International");
            case "003670" -> java.util.List.of("포스코퓨처엠", "포스코퓨처", "POSCO Future M");
            case "005380" -> java.util.List.of("현대자동차", "현대차", "Hyundai Motor");
            case "035420" -> java.util.List.of("NAVER", "네이버");
            case "035720" -> java.util.List.of("카카오", "Kakao");
            case "373220" -> java.util.List.of("LG에너지솔루션", "LG엔솔", "LG Energy Solution");
            default -> java.util.List.of(name(code));
        };
    }
    public static boolean mentions(String code, String text) {
        if (text==null) return false;
        String normalized=text.toLowerCase(java.util.Locale.ROOT);
        return aliases(code).stream().anyMatch(alias -> normalized.contains(alias.toLowerCase(java.util.Locale.ROOT)));
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
