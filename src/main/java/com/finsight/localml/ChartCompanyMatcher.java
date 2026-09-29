package com.finsight.localml;

import java.util.*;
import java.util.regex.Pattern;

/** Chart-only multi-company matching, independent of the legacy single ticker tag. */
public final class ChartCompanyMatcher {
    private ChartCompanyMatcher() {}
    public record Company(List<String> terms, Pattern pattern) {}
    private static Company company(String regex, String... terms) {
        return new Company(List.of(terms), Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }
    private static final Map<String,Company> COMPANIES = Map.ofEntries(
        Map.entry("005930", company("삼성\\s*전자|삼전(?!기)", "삼성전자", "삼전")),
        Map.entry("000660", company("(?:SK\\s*)?하이닉스|(?:SK\\s*)?하닉|삼전닉스", "하이닉스", "하닉", "삼전닉스")),
        Map.entry("035720", company("카카오(?!뱅크|페이|게임즈|뱅|분말|버터|열매)", "카카오")),
        Map.entry("035420", company("네이버|(?<![a-z])NAVER(?![a-z])", "네이버", "naver")),
        Map.entry("373220", company("LG\\s*에너지\\s*솔루션|LG\\s*엔솔|엘지\\s*(?:에너지\\s*솔루션|엔솔)", "에너지솔루션", "엔솔")),
        Map.entry("005380", company("현대\\s*자동차|현대차(?!\\s*증권)", "현대자동차", "현대차")),
        Map.entry("000270", company("(?<![가-힣])기아(?:자동차)?(?!대책|문제|상태)", "기아")),
        Map.entry("207940", company("삼성\\s*바이오\\s*로직스|삼성\\s*바이오(?!\\s*에피스)|삼바(?!춤|축제)", "삼성바이오", "삼바")),
        Map.entry("051910", company("(?:LG|엘지)\\s*화학", "lg화학", "엘지화학")),
        Map.entry("068270", company("셀트리온", "셀트리온")),
        Map.entry("005490", company("포스코홀딩스|POSCO\\s*홀딩스|포스코(?!퓨처엠|인터내셔널|이앤씨)", "포스코", "posco"))
    );
    public static Map<String,String> names() {
        var names=new LinkedHashMap<String,String>();
        names.put("005930","삼성전자"); names.put("000660","SK하이닉스");
        names.put("035420","NAVER"); names.put("035720","카카오"); names.put("373220","LG에너지솔루션");
        names.put("005380","현대차"); names.put("000270","기아"); names.put("207940","삼성바이오로직스");
        names.put("051910","LG화학"); names.put("068270","셀트리온"); names.put("005490","POSCO홀딩스");
        return Collections.unmodifiableMap(names);
    }
    public static String normalize(String text) {
        return text == null ? "" : java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }
    public static boolean searchMatches(String symbol, String query) {
        String key=normalize(query);
        Company c=get(symbol);
        return symbol.contains(key) || normalize(names().get(symbol)).contains(key)
            || c != null && (c.terms().stream().anyMatch(t -> normalize(t).contains(key))
                || reason(symbol,query,"") != null);
    }
    public static boolean mentionsCompany(String title) {
        return names().keySet().stream().anyMatch(s -> reason(s,title,"") != null);
    }
    public static String excerpt(String symbol, String raw, int length) {
        String body=cleanBody(raw);
        var company=get(symbol);
        int start=0;
        if(company!=null) {
            var match=company.pattern().matcher(body);
            if(match.find()) start=Math.max(0,match.start()-400);
        }
        return body.substring(start,Math.min(body.length(),start+length));
    }
    public static Company get(String symbol) { return symbol == null ? null : COMPANIES.get(symbol); }
    public static String cleanBody(String body) {
        if (body == null) return "";
        int end=body.length();
        for (String marker: List.of("제보는 카카오톡", "[저작권자", "<저작권자", "◎공감언론", "▶ 네이버", "▶네이버")) {
            int at=body.indexOf(marker); if (at>=0) end=Math.min(end,at);
        }
        return body.substring(0,end).trim();
    }
    public static String reason(String symbol, String title, String body) {
        Company c=get(symbol); if(c==null) return null;
        if(c.pattern().matcher(title==null ? "" : title).find()) return "제목에 회사명·별칭 언급";
        if(c.pattern().matcher(cleanBody(body)).find()) return "본문에 회사명·별칭 언급";
        return null;
    }
}
