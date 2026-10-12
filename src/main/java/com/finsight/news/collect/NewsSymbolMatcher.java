package com.finsight.news.collect;

import com.finsight.localml.ChartCompanyMatcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
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

    public String match(String title) {
        return match(title, "");
    }

    public String match(String title, String content) {
        String headline = title == null ? "" : title;
        String body = content == null ? "" : content;
        String subject = headline.replaceFirst("^\\s*\\[[^]]+\\]\\s*", "").stripLeading();
        if (subject.matches("^(고용노동부|기획재정부|금융위(?:원회)?|한은|한국은행|정부|국세청|행정안전부|산업통상자원부)(?:[\\s,·]|는|은|이|가|$).*")) return null;
        var explicit = Pattern.compile("\\[(\\d{6})\\]").matcher(headline + " " + body);
        if (explicit.find()) return explicit.group(1);
        Set<String> headlineSymbols = companies(headline);
        boolean multiCompany = headline.contains("삼성·SK하닉") || headline.contains("삼전닉스");
        if (!multiCompany && headlineSymbols.size() == 1) return headlineSymbols.iterator().next();
        if (headline.contains("코스피")) return "KOSPI";
        if (headline.contains("코스닥")) return "KOSDAQ";
        if (multiCompany || headlineSymbols.size() > 1 || headline.matches(".*(환율|금리|물가|美|미국|뉴욕|나스닥|S&P|인텔|엔비디아|애플|테슬라|AMD|TSMC).*")) return null;
        Set<String> bodySymbols = companies(content == null ? "" : content);
        if (bodySymbols.size() == 1) {
            String symbol = bodySymbols.iterator().next();
            var company = ChartCompanyMatcher.get(symbol);
            long count = company != null ? companyPattern(symbol).matcher(body).results().count()
                    : ChartCompanyMatcher.names().entrySet().stream().filter(entry -> entry.getKey().equals(symbol))
                        .mapToLong(entry -> Pattern.compile(Pattern.quote(entry.getValue())).matcher(content).results().count()).sum();
            if (count >= 2) return symbol;
        }
        return null;
    }

    private Pattern companyPattern(String symbol) {
        var company = ChartCompanyMatcher.get(symbol);
        return Pattern.compile("(?<![가-힣A-Za-z])(?:" + company.pattern().pattern() + ")(?![가-힣A-Za-z])", company.pattern().flags());
    }

    private Set<String> companies(String text) {
        Set<String> symbols = new LinkedHashSet<>();
        for (String symbol : ChartCompanyMatcher.names().keySet()) {
            if (companyPattern(symbol).matcher(text).find()) symbols.add(symbol);
        }
        var matched = java.util.List.copyOf(symbols);
        symbols.removeIf(symbol -> matched.stream().anyMatch(other -> !other.equals(symbol)
                && com.finsight.stock.StockCatalog.name(other).contains(com.finsight.stock.StockCatalog.name(symbol))
                && text.contains(com.finsight.stock.StockCatalog.name(other))));
        return symbols;
    }
}
