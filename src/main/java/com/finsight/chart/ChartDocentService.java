package com.finsight.chart;
import com.finsight.external.*;
import com.finsight.news.collect.ArticleContentExtractor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
@Service
public class ChartDocentService {
    private final AiServiceClient ai;
    public ChartDocentService(AiServiceClient ai) { this.ai=ai; }
    @Cacheable(cacheNames="chartDocents", key="#r.symbol() + ':' + #r.date() + ':' + #r.period() + ':' + T(com.finsight.chart.ChartDocentService).representativeId(#r) + ':' + #r.level()", sync=true)
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
        var article = request.news().stream().filter(item -> "DIRECT".equals(item.relation())).findFirst()
                .orElse(request.news().stream().findFirst().orElse(null));
        return article == null ? null : article.newsId();
    }
    static ChartResponse.DocentView rule(AiDocentRequest r) {
        var article=r.news().stream().filter(item -> "DIRECT".equals(item.relation())).findFirst().orElse(r.news().stream().findFirst().orElse(null));
        String relation=article==null?"NONE":article.relation();
        String title=article==null?"":article.title();
        String body=article==null?"":new ArticleContentExtractor().clean(title,article.summary());
        String lead=body.isBlank()?title:body.split("(?<=[.!?])\\s+|\\n+",2)[0];
        if (!"analyst".equals(r.level())) lead = lead.replaceAll("했다\\.$", "했어요.").replaceAll("된다\\.$", "돼요.").replaceAll("이다\\.$", "이에요.");
        String context="W".equals(r.period())?"이 주간에":"MINUTE".equals(r.period())?"이 시점에":"이날";
        String reason;
        if (article==null) { lead="이날 수집된 관련 뉴스가 없어요."; reason="수급이나 시장 전체 요인이 영향을 줬을 수 있어요."; }
        else if ("MARKET".equals(relation)) reason="개별 소식보다 시장 전체 흐름("+(r.indexName()==null?"관련 지수":r.indexName())
                +(r.indexName()!=null && r.indexChangePercent()!=null?String.format(java.util.Locale.ROOT," %+.2f%%",r.indexChangePercent()):"")+")이 영향을 줬을 수 있어요.";
        else {
            var hint=com.finsight.news.SentimentEvidence.direction(article.sentimentHint(),article.sentimentConfidence(),title);
            reason=context+" "+r.symbolName()+" 관련 ‘"+title+"’ 소식이 나왔고, 주가가 "+String.format(java.util.Locale.ROOT,"%+.2f%%",r.changePercent())+" 움직였어요. "
                    +(hint==null?"다른 요인과 함께 시장 반응에 영향을 줬을 수 있어요.":"이 소식이 "+(hint==com.finsight.briefing.SentimentHint.POSITIVE?"호재":"악재")+"로 받아들여졌을 가능성이 있어요.");
        }
        String impact=title.matches(".*(실적|매출|이익).*" )?"실적 변화는 기업 가치 평가에 영향을 줬을 수 있어요."
                :title.matches(".*(규제|정책).*" )?"규제 변화는 기업의 비용과 사업 전망에 영향을 줬을 수 있어요."
                :title.contains("금리")?"금리 변화는 자금 조달 비용과 가치 평가 기준에 영향을 줬을 수 있어요."
                :title.matches(".*(환율|원화).*" )?"환율 변화는 수출 매출과 수입 비용에 영향을 줬을 수 있어요.":"매수·매도 수급과 시장 흐름도 함께 확인해야 해요.";
        return new ChartResponse.DocentView(relation,lead,reason,impact,article==null?null:article.newsId(),article==null?null:title,article==null?null:article.source(),"RULE");
    }
    static com.finsight.briefing.SentimentHint titleSentiment(String title) {
        boolean up=title!=null && title.matches(".*(급등|상향|호실적|수혜).*"), down=title!=null && title.matches(".*(급락|하향|부진|규제 강화).*" );
        return up==down?null:up?com.finsight.briefing.SentimentHint.POSITIVE:com.finsight.briefing.SentimentHint.NEGATIVE;
    }
}
