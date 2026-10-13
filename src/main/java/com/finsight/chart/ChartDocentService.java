package com.finsight.chart;
import com.finsight.external.*;
import com.finsight.news.collect.ArticleContentExtractor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
@Service
public class ChartDocentService {
    private final AiServiceClient ai;
    public ChartDocentService(AiServiceClient ai) { this.ai=ai; }
    @Cacheable(cacheNames="chartDocents", key="'news-direction-v2:' + #r.symbol() + ':' + #r.date() + ':' + #r.period() + ':' + T(com.finsight.chart.ChartDocentService).representativeId(#r) + ':' + #r.level()", sync=true)
    public ChartResponse.DocentView get(AiDocentRequest r) {
        var fallback=rule(r);
        if ("MINUTE".equals(r.period()) || "NONE".equals(fallback.relation())) return fallback;
        return ai.docent(r).filter(value -> fallback.relation().equals(value.relation())
                && java.util.Objects.equals(fallback.newsId(),value.newsId())
                && ("RULE".equals(value.mode()) || "LLM".equals(value.mode()))
                && value.whatHappened()!=null && !value.whatHappened().isBlank()
                && value.possibleReason()!=null && !value.possibleReason().isBlank()
                && value.marketImpact()!=null && !value.marketImpact().isBlank()).orElse(fallback);
    }
    public static Long representativeId(AiDocentRequest request) {
        var article = request.news().stream().findFirst().orElse(null);
        return article == null ? null : article.newsId();
    }
    static ChartResponse.DocentView rule(AiDocentRequest r) {
        var article=r.news().stream().findFirst().orElse(null);
        String relation=article==null?"NONE":article.relation();
        String title=article==null?"":article.title();
        String lead=article==null?"":new ArticleContentExtractor().eventLead(title,article.summary(),java.time.LocalDate.parse(r.date().substring(0,10)),r.changePercent());
        if (!"analyst".equals(r.level())) lead = lead.replaceAll("했다\\.$", "했어요.").replaceAll("된다\\.$", "돼요.").replaceAll("이다\\.$", "이에요.");
        String context="W".equals(r.period())?"이 주간에":"MINUTE".equals(r.period())?"이 시점에":"이날";
        String reason;
        if (article==null) { lead="이날 주가 움직임을 직접 설명하는 뉴스를 찾지 못했어요."; reason="수급이나 시장 전체 요인이 영향을 줬을 수 있어요."; }
        else if ("MARKET".equals(relation)) reason="시장 전체 "+(r.indexChangePercent()!=null && r.indexChangePercent()<0?"하락":r.indexChangePercent()!=null && r.indexChangePercent()>0?"상승":"흐름")+"("+(r.indexName()==null?"관련 지수":r.indexName())
                +(r.indexName()!=null && r.indexChangePercent()!=null?String.format(java.util.Locale.ROOT," %+.2f%%",r.indexChangePercent()):"")+")과 함께 움직였을 수 있어요.";
        else {
            var hint=com.finsight.news.SentimentEvidence.direction(article.sentimentHint(),article.sentimentConfidence(),title);
            reason=context+" "+r.symbolName()+" 관련 ‘"+title+"’ 소식이 나왔고, 주가가 "+String.format(java.util.Locale.ROOT,"%+.2f%%",r.changePercent())+" 움직였어요. "
                    +(hint==null?"다른 요인과 함께 시장 반응에 영향을 줬을 수 있어요.":"이 소식이 "+(hint==com.finsight.briefing.SentimentHint.POSITIVE?"호재":"악재")+"로 받아들여졌을 가능성이 있어요.");
        }
        String evidence=(title+" "+(article==null?"":article.summary())).replaceAll("\\s+"," ");
        String impact=evidence.matches(".*(외국인|순매도|순매수|팔자|수급|매도|매수).*" )?(evidence.matches(".*(외국인).*(순매도|팔자|매도).*" )?"외국인 매도 등 수급 흐름을 함께 확인해요.":"투자 주체별 매수·매도 수급 흐름을 함께 확인해요.")
                :evidence.matches(".*(실적|매출|이익).*" )?"실적 변화와 시장 기대치의 차이를 함께 확인해요."
                :evidence.matches(".*(규제|정책).*" )?"규제 변화는 기업의 비용과 사업 전망에 영향을 줬을 수 있어요."
                :evidence.contains("금리")?"금리 변화는 자금 조달 비용과 가치 평가 기준에 영향을 줬을 수 있어요."
                :evidence.matches(".*(환율|원화).*" )?"환율 변화는 수출 매출과 수입 비용에 영향을 줬을 수 있어요."
                :evidence.matches(".*(반도체|메모리|업황|수요|공급).*" )?"업황과 제품 수요·공급 변화의 지속 여부를 함께 확인해요.":"매수·매도 수급과 시장 흐름도 함께 확인해야 해요.";
        return new ChartResponse.DocentView(relation,lead,reason,impact,article==null?null:article.newsId(),article==null?null:title,article==null?null:article.source(),"RULE");
    }
    static com.finsight.briefing.SentimentHint titleSentiment(String title) {
        boolean up=title!=null && title.matches(".*(급등|상향|호실적|수혜).*"), down=title!=null && title.matches(".*(급락|하향|부진|규제 강화).*" );
        return up==down?null:up?com.finsight.briefing.SentimentHint.POSITIVE:com.finsight.briefing.SentimentHint.NEGATIVE;
    }
}
