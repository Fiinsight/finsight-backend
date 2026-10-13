package com.finsight.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.judgement.KoreanTradingCalendar;
import com.finsight.news.News;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.collect.NewsCategoryClassifier;
import com.finsight.news.collect.NewsSymbolMatcher;
import com.finsight.term.Term;
import com.finsight.term.TermRepository;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;

/** Offline stdin/stdout adapter: no application startup, scheduler, HTTP, or DB writes. */
public final class DemoQuizCatalog {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var input = json.readTree(System.in);
        List<Term> dictionary = new ArrayList<>();
        for (var row : input.path("terms")) dictionary.add(new Term(row.path("term").asText(), row.path("short_definition").asText()));
        var repository = (TermRepository) Proxy.newProxyInstance(TermRepository.class.getClassLoader(),
            new Class<?>[]{TermRepository.class}, (proxy, method, values) -> {
                if (method.getName().equals("findAll")) return dictionary;
                throw new UnsupportedOperationException("Read-only demo dictionary");
            });
        var learning = new LearningService(null, null, repository, null, null, null, new ArticleContentExtractor(), null, null);
        var id = News.class.getDeclaredField("id");
        id.setAccessible(true); // Restore an existing persisted ID for its exact deterministic quiz seed.
        var categories = new NewsCategoryClassifier();
        var symbols = new NewsSymbolMatcher();
        List<Map<String,Object>> questions = new ArrayList<>();
        List<Object> articles = new ArrayList<>();
        for (var row : input.path("news")) {
            var article = new News(row.path("title").asText(), row.path("url").asText(), row.path("source").asText(),
                Instant.parse(row.path("published_at").asText()), row.path("raw_content").asText());
            id.set(article, row.path("id").asLong());
            articles.add(Map.of("id", article.getId(), "category", categories.classify(article.getTitle(),article.getRawContent()),
                "validSymbol", Objects.equals(symbols.match(article.getTitle(),article.getRawContent()),row.path("related_symbol").asText())));
            for (var term : learning.articleTerms(article)) for (var level : List.of("beginner","normal","analyst")) {
                var question = learning.question(article,term,level);
                if (question != null) questions.add(Map.of("newsId",article.getId(),"term",term.getTerm(),
                    "requestedLevel",level,"level",question.level(),"prompt",question.publicQuestion().prompt(),
                    "options",question.publicQuestion().options(),"correctIndex",question.publicQuestion().options().indexOf(question.correctAnswer())));
            }
        }
        List<Object> prices = new ArrayList<>();
        for (var row : input.path("prices")) {
            var day = LocalDate.parse(row.path("day").asText());
            var comparison = LocalDate.parse(row.path("comparison_date").asText());
            if (KoreanTradingCalendar.isTradingDay(day) && KoreanTradingCalendar.next(day).equals(comparison)) prices.add(row);
        }
        Map<String,Double> probabilities = new LinkedHashMap<>();
        Map<String,Integer> attempts = new LinkedHashMap<>();
        for (var row : input.path("attempts")) {
            String key = row.path("owner").asText()+":"+row.path("term").asText();
            probabilities.put(key,KnowledgeTracing.update(probabilities.getOrDefault(key,KnowledgeTracing.P_INIT),row.path("correct").asBoolean(),row.path("level").asText()));
            attempts.merge(key,1,Integer::sum);
        }
        List<Object> selections = new ArrayList<>();
        for (String owner : List.of("demo-beginner","demo-investor")) {
            String level=owner.equals("demo-beginner")?"beginner":"analyst";
            Map<Long,List<Map<String,Object>>> byNews=new LinkedHashMap<>();
            questions.stream().filter(q -> level.equals(q.get("requestedLevel"))).forEach(q ->
                byNews.computeIfAbsent((Long)q.get("newsId"),ignored -> new ArrayList<>()).add(q));
            Map<String,Double> known=new HashMap<>();
            probabilities.forEach((key,p) -> { if(key.startsWith(owner+":")) known.put(key.substring(owner.length()+1),p); });
            byNews.forEach((newsId,choices) -> {
                String selected=KnowledgeTracing.select(choices.stream().map(q -> (String)q.get("term")).toList(),known);
                var q=choices.stream().filter(value -> selected.equals(value.get("term"))).findFirst().orElseThrow();
                double prior=known.getOrDefault(selected,KnowledgeTracing.P_INIT);
                selections.add(Map.of("owner",owner,"newsId",newsId,"term",selected,"level",q.get("level"),
                    "pKnown",prior,"afterCorrect",KnowledgeTracing.update(prior,true,(String)q.get("level"))));
            });
        }
        System.out.println("DEMO_JSON:"+json.writeValueAsString(Map.of("questions",questions,"articles",articles,"prices",prices,"probabilities",probabilities,"attempts",attempts,"selections",selections)));
    }
}
