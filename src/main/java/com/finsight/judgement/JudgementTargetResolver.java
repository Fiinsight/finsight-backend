package com.finsight.judgement;

import com.finsight.news.News;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.collect.NewsSymbolMatcher;
import com.finsight.stock.StockCatalog;

/** Computes a new judgement target without changing the article's stored mapping. */
public final class JudgementTargetResolver {
    public record Target(String symbol, String name, String unavailableReason) {}
    private static final NewsSymbolMatcher MATCHER = new NewsSymbolMatcher();
    private JudgementTargetResolver() {}
    public static Target resolve(News news) {
        if (news == null) return unavailable();
        String title = java.util.Objects.toString(news.getTitle(), "");
        String body = new ArticleContentExtractor().clean(title, news.getRawContent());
        if (body == null || body.isBlank() || title.matches("(?s).*(\\[부고\\]|\\[인사\\]|訃告|별세|부음).*")) return unavailable();
        String mapped = MATCHER.match(title, body);
        if (valid(mapped)) return new Target(mapped, name(mapped), null);
        String text = title + " " + body;
        if (title.matches("(?s).*(미국|뉴욕|나스닥|S&P|일본|중국|유럽|美|日|中).*" )
                && !text.matches("(?s).*(코스피|코스닥|국내|한국|서울|한은|원화|원달러|원/달러).*")) return unavailable();
        if (!text.matches("(?s).*(경제|증시|기업|금리|환율|금융|채권|물가|투자|수출|수입|매출|실적|반도체|인공지능|AI|에너지|규제|고용|노동).*")) return unavailable();
        String symbol = text.contains("코스닥") && !text.contains("코스피") ? "KOSDAQ" : "KOSPI";
        return new Target(symbol, name(symbol), null);
    }
    public static boolean valid(String symbol) {
        return symbol != null && (symbol.equals("KOSPI") || symbol.equals("KOSDAQ") || StockCatalog.entries().containsKey(symbol));
    }
    public static String name(String symbol) {
        return "KOSPI".equals(symbol) ? "코스피" : "KOSDAQ".equals(symbol) ? "코스닥" : StockCatalog.name(symbol);
    }
    private static Target unavailable() { return new Target(null, null, "판단 대상이 정해지지 않은 기사예요"); }
}
