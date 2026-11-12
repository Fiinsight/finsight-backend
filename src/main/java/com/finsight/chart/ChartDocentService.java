package com.finsight.chart;
import com.finsight.external.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
@Service
public class ChartDocentService {
    private final AiServiceClient ai;
    @org.springframework.beans.factory.annotation.Value("${DOCENT_WITH_LLM:false}")
    private boolean withLlm;
    public ChartDocentService(AiServiceClient ai) { this.ai=ai; }
    @Cacheable(cacheNames="chartDocents", key="T(com.finsight.chart.ChartDocentService).cacheKey(#r)", sync=true)
    public ChartResponse.DocentView get(AiDocentRequest r) {
        return compute(r, java.time.Duration.ofSeconds(3));
    }
    @Cacheable(cacheNames="chartDocents", key="T(com.finsight.chart.ChartDocentService).cacheKey(#r)", sync=true)
    public ChartResponse.DocentView getWithin(AiDocentRequest r, java.time.Duration remaining) {
        return compute(r, remaining);
    }
    private ChartResponse.DocentView compute(AiDocentRequest r, java.time.Duration remaining) {
        var fallback=rule(r);
        if (!withLlm || "MINUTE".equals(r.period()) || "NONE".equals(fallback.relation())) return fallback;
        return ai.docent(r, remaining).filter(value -> fallback.relation().equals(value.relation())
                && java.util.Objects.equals(fallback.newsId(),value.newsId())
                && ("RULE".equals(value.mode()) || "LLM".equals(value.mode()))
                && value.whatHappened()!=null && !value.whatHappened().isBlank()
                && value.possibleReason()!=null && !value.possibleReason().isBlank()
                && value.marketImpact()!=null && !value.marketImpact().isBlank()
                && safeOutput(r,value)).orElse(fallback);
    }
    private static final java.util.regex.Pattern NUMBER=java.util.regex.Pattern.compile("(?<![\\d.])[-+]?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:[조억만천]\\d+)*[조억만천]*(?:%p|%포인트|%|퍼센트|원|달러|엔|유로|배|명|건|년|월|일)?");
    private static java.util.Set<String> numbers(String raw) {
        String text=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFKC).replace('−','-')
                .replaceAll("([+-])\\s+(?=\\d)","$1").replaceAll("퍼센트\\s*포인트|%\\s*포인트","%p").replace("퍼센트","%")
                .replaceAll("(?<=[\\d조억만천])\\s+(?=[조억만천원달러엔유로배명건년월일%])","")
                .replaceAll("(?<=[조억만천])\\s+(?=\\d)","").replaceAll("(?<=\\d),(?=\\d)","");
        return NUMBER.matcher(text).results().map(match -> {
            var digits=java.util.regex.Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(match.group());
            return digits.replaceAll(n -> new java.math.BigDecimal(n.group()).stripTrailingZeros().toPlainString());
        }).collect(java.util.stream.Collectors.toSet());
    }
    static boolean safeOutput(AiDocentRequest r,ChartResponse.DocentView value) {
        String text=value.whatHappened()+" "+value.possibleReason()+" "+value.marketImpact();
        if (java.util.regex.Pattern.compile("때문에|원인이다|원인입니다|확실히|보장|매수하세요|매도하세요").matcher(text).find()) return false;
        String source=r.news().stream().map(a -> a.title()+" "+a.summary()+" "+java.util.Objects.toString(a.causePhrase(),""))
                .collect(java.util.stream.Collectors.joining(" "))+String.format(java.util.Locale.ROOT," %+.2f%%",r.changePercent());
        if(r.indexChangePercent()!=null) source+=String.format(java.util.Locale.ROOT," %+.2f%% %+.2f%%p %.2f%%p",r.indexChangePercent(),ChartTextEvidence.displayedExcess(r),Math.abs(ChartTextEvidence.displayedExcess(r)));
        return numbers(source).containsAll(numbers(text));
    }
    // Local Caffeine keys compare immutable source fields, without re-encoding all article text on every hit.
    public static AiDocentRequest cacheKey(AiDocentRequest r) { return r; }
    public static Long representativeId(AiDocentRequest request) {
        var article = request.news().stream().findFirst().orElse(null);
        return article == null ? null : article.newsId();
    }
    static ChartResponse.DocentView rule(AiDocentRequest r) {
        var article=r.news().stream().findFirst().orElse(null);
        String relation=article==null?"NONE":article.relation();
        String title=article==null?"":article.title();
        String lead=article==null?"":ChartTextEvidence.lead(r.symbol(),title,article.summary(),java.time.LocalDate.parse(r.date().substring(0,10)),r.changePercent(),relation);
        lead=ChartTextEvidence.polite(lead,r.level());
        if (article==null) lead=("W".equals(r.period())?"이 주간":"MINUTE".equals(r.period())?"이 시점":"이날")+" 주가 움직임을 직접 설명하는 뉴스를 찾지 못했어요.";
        String reason=ChartTextEvidence.reason(r,article);
        String impact=ChartTextEvidence.impact(r,article,lead);
        return new ChartResponse.DocentView(relation,lead,reason,impact,article==null?null:article.newsId(),article==null?null:title,article==null?null:article.source(),"RULE",r.terms());
    }
    static com.finsight.briefing.SentimentHint titleSentiment(String title) {
        return com.finsight.news.SentimentEvidence.titleDirection(title);
    }
}
