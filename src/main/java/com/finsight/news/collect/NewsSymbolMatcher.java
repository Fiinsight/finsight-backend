package com.finsight.news.collect;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Infers a related stock/index symbol from a headline via keyword rules, so
 * collected news can drive the judgement and chart-docent features the same
 * way the original hardcoded sample data did (relatedSymbol was previously
 * only ever set on that sample data, never on real collected news).
 */
@Component
public class NewsSymbolMatcher {

    // Company name -> ticker, checked before the market-wide fallback below.
    // Covers the same "인기 종목" set the frontend already shows as samples.
    private static final Map<String, String> COMPANY_SYMBOLS = new LinkedHashMap<>();

    static {
        COMPANY_SYMBOLS.put("삼성전자", "005930");
        COMPANY_SYMBOLS.put("삼성전기", "009150");
        COMPANY_SYMBOLS.put("삼성바이오로직스", "207940");
        COMPANY_SYMBOLS.put("삼전", "005930");
        COMPANY_SYMBOLS.put("SK하이닉스", "000660");
        COMPANY_SYMBOLS.put("SK하닉", "000660");
        COMPANY_SYMBOLS.put("네이버", "035420");
        COMPANY_SYMBOLS.put("NAVER", "035420");
        COMPANY_SYMBOLS.put("카카오", "035720");
        COMPANY_SYMBOLS.put("LG에너지솔루션", "373220");
        COMPANY_SYMBOLS.put("현대차", "005380");
        COMPANY_SYMBOLS.put("현대로템", "064350");
        COMPANY_SYMBOLS.put("기아", "000270");
        COMPANY_SYMBOLS.put("삼성바이오로직스", "207940");
        COMPANY_SYMBOLS.put("LG화학", "051910");
        COMPANY_SYMBOLS.put("셀트리온", "068270");
        COMPANY_SYMBOLS.put("POSCO홀딩스", "005490");
        COMPANY_SYMBOLS.put("포스코홀딩스", "005490");
    }

    public String match(String title) {
        return match(title, "");
    }

    public String match(String title, String content) {
        String headline = title == null ? "" : title;
        Set<String> headlineSymbols = companies(headline);
        if (headline.contains("삼전닉스") || headline.contains("삼성·SK하닉")) {
            return headline.contains("코스피") ? "KOSPI" : null;
        }
        if (headlineSymbols.size() == 1) return headlineSymbols.iterator().next();
        if (headline.contains("코스닥")) return "KOSDAQ";
        if (headline.contains("코스피")) return "KOSPI";
        if (headlineSymbols.size() > 1 || headline.matches(".*(美|미국|뉴욕|나스닥|S&P).*")) return null;
        Set<String> bodySymbols = companies(content == null ? "" : content);
        if (bodySymbols.size() == 1) return bodySymbols.iterator().next();
        return null;
    }

    private Set<String> companies(String text) {
        Set<String> symbols = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : COMPANY_SYMBOLS.entrySet()) {
            if (text.contains(entry.getKey())) symbols.add(entry.getValue());
        }
        return symbols;
    }
}
