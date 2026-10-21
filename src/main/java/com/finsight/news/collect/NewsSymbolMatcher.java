package com.finsight.news.collect;

import com.finsight.stock.StockCatalog;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Single-stock tag for newly collected news; market headlines retain their index target. */
@Component
public class NewsSymbolMatcher {
    private static final Map<String,List<Pattern>> PATTERNS = patterns();
    private record Hits(int count, int first) {}
    private record Mention(String symbol,int start,int end) {}
    public String match(String title) { return match(title, ""); }
    public String match(String title, String content) {
        String headline=Objects.toString(title,""), body=com.finsight.localml.ChartCompanyMatcher.cleanBody(content);
        String subject=headline.replaceFirst("^\\s*\\[[^]]+\\]\\s*", "").stripLeading();
        if(subject.matches("^(고용노동부|기획재정부|금융위(?:원회)?|한은|한국은행|정부|국세청|행정안전부|산업통상자원부)(?:[\\s,·]|는|은|이|가|$).*")) return null;
        Map<String,Hits> inTitle=hits(headline), inBody=hits(body);
        if(inTitle.size()==1) return inTitle.keySet().iterator().next();
        if(headline.contains("코스피")) return "KOSPI";
        if(headline.contains("코스닥")) return "KOSDAQ";
        if(!inTitle.isEmpty()) return inTitle.keySet().stream().sorted(Comparator
                .<String>comparingInt(symbol->inBody.getOrDefault(symbol,new Hits(0,0)).count()).reversed()
                .thenComparingInt(symbol->inTitle.get(symbol).first()).thenComparing(Comparator.naturalOrder())).findFirst().orElse(null);
        if(headline.matches(".*(환율|금리|물가|美|미국|뉴욕|나스닥|S&P|인텔|엔비디아|애플|테슬라|AMD|TSMC).*")) return null;
        // Without a headline company, a tied incidental body mention is not enough to pick a stock.
        int max=inBody.values().stream().mapToInt(Hits::count).max().orElse(0);
        var leaders=inBody.entrySet().stream().filter(entry->entry.getValue().count()==max).toList();
        return leaders.size()==1 ? leaders.getFirst().getKey() : null;
    }
    private static Map<String,Hits> hits(String text) {
        List<Mention> mentions=new ArrayList<>();
        for(var entry:PATTERNS.entrySet()) for(var pattern:entry.getValue()) pattern.matcher(text).results().forEach(match->{
            if(entry.getKey().equals("000270") && !match.group().startsWith("[")
                    && !text.matches("(?s).*(기아.{0,12}(자동차|실적|주가|매출|영업|신차|전기차)|완성차.{0,12}기아).*")) return;
            mentions.add(new Mention(entry.getKey(),match.start(),match.end()));
        });
        Map<String,Hits> hits=new LinkedHashMap<>();
        for(var entry:PATTERNS.entrySet()) {
            var ranges=mentions.stream().filter(m->m.symbol().equals(entry.getKey()))
                    .filter(m->mentions.stream().noneMatch(other->!other.symbol().equals(m.symbol()) && other.start()<=m.start() && other.end()>m.end()))
                    .sorted(Comparator.comparingInt(Mention::start).thenComparing(Comparator.comparingInt(Mention::end).reversed())).toList();
            int count=0,end=-1,first=-1;
            for(var range:ranges) {
                if(range.start()>end) { count++; if(first<0) first=range.start(); }
                end=Math.max(end,range.end());
            }
            if(count>0) hits.put(entry.getKey(),new Hits(count,first));
        }
        return hits;
    }
    private static Map<String,List<Pattern>> patterns() {
        Map<String,List<Pattern>> result=new LinkedHashMap<>();
        // Generic group names and noun aliases (삼성/현대/삼바/기아) are deliberately not introduced as nicknames.
        Map<String,List<String>> extra=Map.of(
                "035420",List.of("네이버"), "005380",List.of("현대차","현대자동차"),
                "000660",List.of("SK하닉"), "373220",List.of("LG엔솔","엘지에너지솔루션"),
                "051910",List.of("엘지화학"), "005490",List.of("POSCO홀딩스"));
        StockCatalog.entries().forEach((symbol,stock)->{
            var aliases=new LinkedHashSet<>(StockCatalog.aliases(symbol)); aliases.add(stock.name()); aliases.addAll(extra.getOrDefault(symbol,List.of()));
            List<Pattern> patterns=new ArrayList<>();
            patterns.add(Pattern.compile("\\["+symbol+"\\]"));
            for(String alias:aliases) {
                String literal=alias.chars().filter(c->!Character.isWhitespace(c)).mapToObj(c->Pattern.quote(String.valueOf((char)c))).collect(java.util.stream.Collectors.joining("\\s*"));
                String end=alias.length()<=2 ? "(?![가-힣A-Za-z0-9])" : "(?=$|[^가-힣A-Za-z0-9]|(?:은|는|이|가|의|을|를|와|과|에|로|도|만)(?![A-Za-z]))";
                // Exclude ordinary cacao uses and subsidiary names, including names outside this catalog snapshot.
                if(alias.equals("카카오")) end="(?!\\s*(톡|뱅|페이|게임즈|분말|버터|열매))"+end;
                if(alias.equals("기아")) end="(?!대책|문제|상태)"+end;
                if(alias.equals("현대차")) end="(?!증권|\\s+증권)"+end;
                patterns.add(Pattern.compile("(?<![가-힣A-Za-z0-9])"+literal+end,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE));
            }
            result.put(symbol,List.copyOf(patterns));
        });
        return Collections.unmodifiableMap(result);
    }
}
