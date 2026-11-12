package com.finsight.chart;

import com.finsight.localml.ChartCompanyMatcher;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.stock.StockCatalog;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import com.finsight.external.AiDocentRequest;

/** Selects source sentences; neither invents events nor infers causation from price direction. */
final class ChartTextEvidence {
    private static final Pattern EVENT=Pattern.compile("발표|공시|수주|계약|실적|가이던스|인수|소송|제재|승인|리콜|순매도|순매수|금리|관세");
    private static final Pattern MARKET=Pattern.compile("코스피|코스닥|증시|주식시장|주식 시장|주가지수|FOMC|나스닥",Pattern.CASE_INSENSITIVE);
    static String lead(String symbol,String title,String body,LocalDate day,double change,String relation) {
        var direction=Pattern.compile(change<0?"하락|급락|약세|매도":"상승|급등|강세|매수");
        var sentences=new ArticleContentExtractor().eventSentences(title,body,day);
        return sentences.stream().filter(s -> s.length()<=400 && subject(symbol,s,relation))
                .max(Comparator.comparingInt((String s) -> EVENT.matcher(s).find()?2:0)
                        .thenComparingInt(s -> direction.matcher(s).find()?1:0)
                        .thenComparingInt(s -> -sentences.indexOf(s)))
                .orElse(subject(symbol,title,relation)?title:"DIRECT".equals(relation)
                        ?"이 종목을 직접 다루는 문장을 확인하지 못했어요.":"이 시점의 관련 보도를 확인했어요.");
    }
    private static boolean subject(String symbol,String text,String relation) {
        if ("DIRECT".equals(relation)) return ChartCompanyMatcher.reason(symbol,text,"")!=null;
        if ("SECTOR".equals(relation)) return StockCatalog.sectorTerms(symbol).stream().anyMatch(text::contains);
        return "MARKET".equals(relation) && MARKET.matcher(text).find();
    }
    static String polite(String text,String level) {
        if ("analyst".equals(level)) return text;
        for (var pair:List.of(new String[]{"늘었다","늘었어요"},new String[]{"줄었다","줄었어요"},
                new String[]{"올랐다","올랐어요"},new String[]{"내렸다","내렸어요"},new String[]{"했다","했어요"},
                new String[]{"됐다","됐어요"},new String[]{"있다","있어요"},new String[]{"없다","없어요"},
                new String[]{"한다","해요"},new String[]{"된다","돼요"},new String[]{"이다","이에요"}))
            text=text.replaceAll(Pattern.quote(pair[0])+"(?=[.!?。！？]|$)",pair[1]);
        return text;
    }
    private static final Pattern CAUSE=Pattern.compile("([^.!?。！？\\n]{2,90}?(?:소식에|우려에|기대에|영향으로|여파로|발표 후|매도세에|매수세에|으로|로|에))\\s*(?:(?:[+-]?\\d+(?:\\.\\d+)?%|주가가|주가는|장중|이날|오늘)\\s*){0,4}(?:급등|급락|상승|하락|강세|약세)");
    private static final Pattern CAUSE_EVENT=Pattern.compile("실적|이익|매출|수주|계약|금리|환율|관세|매도|매수|수급|우려|기대|수요|공급|발표|공시|규제|정책|인수|소송|제재|승인|리콜");
    static String cause(AiDocentRequest r,AiDocentRequest.Article article) {
        if (article==null) return null;
        if (article.causePhrase()!=null) return article.causePhrase();
        return cause(r.symbol(),r.date(),article.relation(),article.title(),article.summary());
    }
    static String cause(String symbol,String date,String relation,String title,String body) {
        var parts=new ArrayList<String>();parts.add(title);
        parts.addAll(new ArticleContentExtractor().eventSentences(title,body,LocalDate.parse(date.substring(0,10))));
        for (String part:parts) {
            if (!subject(symbol,part,relation)) continue;
            var matcher=CAUSE.matcher(part);
            while (matcher.find()) {
                String phrase=matcher.group(1).strip();
                if (CAUSE_EVENT.matcher(phrase).find() && !phrase.contains("에도") && !phrase.contains("지만")) return phrase;
            }
        }
        return null;
    }
    static String comparison(AiDocentRequest r) {
        if (r.indexChangePercent()==null || r.indexName()==null) return "";
        double excess=displayedExcess(r);
        String context=r.marketContext()!=null?r.marketContext():ChartService.marketContext(r.changePercent(),r.indexChangePercent(),com.finsight.judgement.JudgementFeedbackRules.MARKET_DIFFERENCE_PERCENT);
        String position=Math.abs(r.changePercent())<=0.5?"":"FOLLOWING".equals(context)?"시장과 비슷한 방향·폭으로, ":"OPPOSITE".equals(context)?"시장과 반대 방향으로, ":"";
        String period="W".equals(r.period())?"같은 주간":"MINUTE".equals(r.period())?"같은 시점":"같은 날";
        if ("analyst".equals(r.level()))
            return String.format(Locale.ROOT,"%s %s %+.2f%%·%s %+.2f%%로, %s초과 등락률은 %+.2f%%p예요.",period,r.indexName(),r.indexChangePercent(),r.symbolName(),r.changePercent(),position,excess);
        return String.format(Locale.ROOT,"%s %s %+.2f%%, %s %+.2f%%로, %s등락률이 시장보다 %.2f%%p %s.",
                period,topic(r.indexName()),r.indexChangePercent(),topic(r.symbolName()),r.changePercent(),position,Math.abs(excess),excess>=0?"높았어요":"낮았어요");
    }
    static String topic(String word) {
        char last=word.charAt(word.length()-1);
        return word+(last>='가' && last<='힣' && (last-'가')%28!=0?"은":"는");
    }
    static double displayedExcess(AiDocentRequest r) {
        // The displayed difference must agree with the two displayed (two-decimal) returns.
        return new java.math.BigDecimal(String.format(Locale.ROOT,"%.2f",r.changePercent()))
                .subtract(new java.math.BigDecimal(String.format(Locale.ROOT,"%.2f",r.indexChangePercent()))).doubleValue();
    }
    static String reason(AiDocentRequest r,AiDocentRequest.Article article) {
        String comparison=comparison(r);
        if (article==null) return comparison.isEmpty()?String.format(Locale.ROOT,"주가는 %+.2f%% 움직였지만, 이 시점을 설명하는 뉴스와 시장 자료를 찾지 못했어요.",r.changePercent()):comparison+" 가격 비교만으로 원인을 단정하지 않아요.";
        String title=article.title();
        String sentimentTitle=title.contains("에도")?title.substring(0,title.indexOf("에도")):title;
        var sentiment=com.finsight.news.SentimentEvidence.direction(article.sentimentHint(),article.sentimentConfidence(),sentimentTitle);
        boolean neutral=Math.abs(r.changePercent())<=0.5;
        boolean opposite=!neutral && (sentiment==com.finsight.briefing.SentimentHint.POSITIVE && r.changePercent()<0 || sentiment==com.finsight.briefing.SentimentHint.NEGATIVE && r.changePercent()>0);
        String first;
        String cause=cause(r,article);
        if (neutral) first="MARKET".equals(article.relation())?"시장이 움직인 날에도 주가는 거의 변하지 않았어요.":"관련 소식이 나왔지만 주가는 거의 움직이지 않았어요.";
        else if (opposite) first=(sentiment==com.finsight.briefing.SentimentHint.POSITIVE?"호재":"악재")+" 소식에도 주가는 "+(r.changePercent()<0?"내렸어요":"올랐어요")+", 다른 원인은 이 기사만으로 확인하기 어려워요.";
        else if (cause!=null) first=(article.afterCloseReport()?"마감 후 보도의 ":"기사의 ")+"‘"+cause+"’ 표현처럼 해당 요인이 영향을 줬을 수 있어요.";
        else first=(article.afterCloseReport()?"마감 후 관련 소식: ‘":"관련 소식: ‘")+title+"’.";
        if ("SECTOR".equals(article.relation())) first=first.substring(0,first.length()-1)+", 이 종목을 직접 다룬 기사는 아니에요.";
        if (!comparison.isEmpty() && !neutral && "FOLLOWING".equals(r.marketContext()!=null?r.marketContext():ChartService.marketContext(r.changePercent(),r.indexChangePercent(),1.0))) return comparison+" "+first;
        return first+" "+(comparison.isEmpty()?String.format(Locale.ROOT,"주가는 %+.2f%% 움직였지만, 같은 기간 지수 자료는 확인하지 못했어요.",r.changePercent()):comparison);
    }
    static String impact(AiDocentRequest r,AiDocentRequest.Article article,String lead) {
        String evidence=article==null?"":article.title()+" "+lead;
        String next=Pattern.compile("수주|계약").matcher(evidence).find()?"계약 규모·기간 공시를 확인해요"
                :Pattern.compile("실적|매출|영업이익").matcher(evidence).find()?"발표 실적과 시장 예상치의 차이를 확인해요"
                :Pattern.compile("규제|정책|관세").matcher(evidence).find()?"발표한 정책의 시행 시점을 확인해요"
                :Pattern.compile("(?:외국인|기관).{0,20}(?:순매수|순매도|매도세|매수세|매매)|수급|프로그램 매매").matcher(evidence).find()?"이후 외국인·기관 매매가 이어지는지 확인해요"
                :"추가로 확인할 개별 사건 자료가 없어요";
        if (r.indexChangePercent()==null) return article==null || next.startsWith("추가로")?"추가로 확인할 시장 자료가 없어요.":next+".";
        return String.format(Locale.ROOT,"시장 대비 초과 등락률은 %+.2f%%p이며, %s.",displayedExcess(r),next);
    }
}
