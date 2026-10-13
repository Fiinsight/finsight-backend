package com.finsight.judgement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.external.AiFeedbackRequest;
import com.finsight.news.News;
import java.time.*;
import java.util.*;

/** Offline planner: only stdin and stdout; no application startup, HTTP, or writes. */
public final class JudgementRegradePlan {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var input = json.readTree(System.in);
        Map<String, Double> closes = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (var row : input.path("prices")) {
            String key = row.path("symbol").asText() + ":" + KoreanTradingCalendar.onOrBefore(LocalDate.parse(row.path("day").asText()));
            double value = row.path("close").asDouble();
            if (!(value > 0) || !Double.isFinite(value)) continue;
            Double previous = closes.putIfAbsent(key,value);
            if (previous != null && Math.abs(previous-value) > 0.0001) conflicting.add(key);
        }
        conflicting.forEach(closes::remove);
        var dictionary = new ArrayList<com.finsight.term.Term>();
        for (var row : input.path("terms")) dictionary.add(new com.finsight.term.Term(row.path("term").asText(),row.path("short_definition").asText()));
        var terms = (com.finsight.term.TermRepository) java.lang.reflect.Proxy.newProxyInstance(com.finsight.term.TermRepository.class.getClassLoader(),
                new Class<?>[]{com.finsight.term.TermRepository.class},(proxy,method,values) -> { if (method.getName().equals("findAll")) return dictionary; throw new UnsupportedOperationException("Read only"); });
        var mastery = new com.finsight.learning.TermMasteryService(null) {
            @Override public List<Mastery> list(Long userId) {
                var result = new ArrayList<Mastery>();
                for (var row : input.path("mastery")) if (row.path("user_id").asLong() == userId) {
                    Instant at = row.path("last_answered_at").isNull() ? null : Instant.parse(row.path("last_answered_at").asText());
                    long days = at == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(at.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),LocalDate.now(ZoneId.of("Asia/Seoul")));
                    double p = row.path("p_known").asDouble();
                    result.add(new Mastery(row.path("term").asText(),p,com.finsight.learning.KnowledgeTracing.decay(p,days),row.path("attempts").asInt(),at,"beginner",null));
                }
                return result;
            }
        };
        var learning = new com.finsight.learning.LearningService(null,null,terms,null,null,null,new com.finsight.news.collect.ArticleContentExtractor(),mastery,null);
        var id = News.class.getDeclaredField("id"); id.setAccessible(true);
        List<Map<String,Object>> plans = new ArrayList<>();
        for (var row : input.path("judgements")) {
            var article = new News(row.path("title").asText(),row.path("url").asText(),row.path("source").asText(),Instant.now(),row.path("raw_content").asText());
            id.set(article,row.path("news_id").asLong());
            var target = JudgementTargetResolver.resolve(article);
            LocalDate judged = Instant.parse(row.path("created_at").asText()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            LocalDate before = KoreanTradingCalendar.onOrBefore(judged), after = KoreanTradingCalendar.next(judged);
            Map<String,Object> plan = new LinkedHashMap<>();
            plan.put("id", row.path("id").asLong());
            plan.put("previousTarget", row.path("related_symbol").isNull() ? null : row.path("related_symbol").asText());
            plan.put("target",target.symbol()); plan.put("baselineDate",before); plan.put("comparisonDate",after);
            if (target.symbol() == null) { plan.put("status","NO_TARGET"); plans.add(plan); continue; }
            if (after.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))) { plan.put("status","NOT_DUE"); plans.add(plan); continue; }
            String symbol = target.symbol();
            Double start = closes.get(symbol+":"+before), end = closes.get(symbol+":"+after);
            String index = "KOSDAQ".equals(com.finsight.stock.StockCatalog.market(symbol)) ? "KOSDAQ" : "KOSPI";
            boolean indexTarget = List.of("KOSPI","KOSDAQ").contains(symbol);
            Double indexStart = indexTarget ? null : closes.get(index+":"+before), indexEnd = indexTarget ? null : closes.get(index+":"+after);
            if (start == null || end == null || (!indexTarget && (indexStart == null || indexEnd == null))) {
                plan.put("status","NO_SAVED_CLOSES"); plans.add(plan); continue;
            }
            double percent = new ClosingPrices.Pair(start,end).percent();
            String body = new com.finsight.news.collect.ArticleContentExtractor().clean(article.getTitle(),article.getRawContent());
            var concept = learning.feedbackConcept(row.path("user_id").asLong(),article,row.path("learning_level").asText("beginner"));
            var request = new AiFeedbackRequest(row.path("news_id").asLong(),row.path("choice").asText(),row.path("reason_text").asText(""),
                    JudgementFeedbackRules.direction(percent),article.getTitle(),body.substring(0,Math.min(body.length(),600)),symbol,target.name(),percent,
                    java.math.BigDecimal.valueOf(start),java.math.BigDecimal.valueOf(end),after,row.path("learning_level").asText("beginner"),
                    indexTarget ? null : JudgementTargetResolver.name(index),indexTarget ? null : new ClosingPrices.Pair(indexStart,indexEnd).percent(),
                    row.path("sentiment_hint").asText("NEUTRAL"), row.path("sentiment_confidence").isNumber() ? row.path("sentiment_confidence").asDouble() : null,concept == null ? null : concept.term(),concept == null ? null : concept.definition(),row.path("id").asLong());
            plan.put("status","READY"); plan.put("actualDirection",request.marketResult()); plan.put("actualChangePercent",percent);
            plan.put("feedback",JudgementFeedbackRules.generate(request)); plans.add(plan);
        }
        System.out.println("REGRADE_JSON:"+json.writeValueAsString(plans));
    }
}
