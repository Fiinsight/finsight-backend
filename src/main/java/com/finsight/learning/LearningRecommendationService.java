package com.finsight.learning;

import com.finsight.news.*;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.external.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningRecommendationService {
    public record Recommendation(Long newsId, String title, String category, String summary, String reason, Long reasonNewsId, String reasonTitle, String basis, Double score) {}
    public record Response(List<Recommendation> items, String basis) {}
    record Activity(String type, String term) {}
    private final JdbcTemplate jdbc;
    private final NewsRepository news;
    private final AiServiceClient ai;
    private final ArticleContentExtractor extractor;
    private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
    public LearningRecommendationService(JdbcTemplate jdbc, NewsRepository news, AiServiceClient ai, ArticleContentExtractor extractor) {
        this.jdbc=jdbc; this.news=news; this.ai=ai; this.extractor=extractor;
    }
    @Transactional(readOnly=true)
    public Response get(Long userId, List<Long> readIds) {
        Set<String> watched=new HashSet<>(jdbc.queryForList("SELECT symbol FROM user_watchlist WHERE user_id=? ORDER BY created_at,id",String.class,userId));
        Instant now=clock.instant(), since=now.minus(Duration.ofDays(30));
        var activities=jdbc.queryForList("""
                SELECT news_id,sum(weight) AS weight,
                    (array_agg(activity_type ORDER BY weight DESC,activity_at DESC,term))[1] AS activity_type,
                    (array_agg(term ORDER BY weight DESC,activity_at DESC,term))[1] AS term FROM (
                    SELECT news_id,1.0 AS weight,'QUIZ' AS activity_type,term,answered_at AS activity_at FROM learning_progress WHERE user_id=? AND NOT correct AND answered_at>=? AND answered_at<=?
                    UNION ALL SELECT news_id,0.8 AS weight,'MEMO',NULL,updated_at FROM article_note WHERE user_id=? AND updated_at>=? AND updated_at<=?
                    UNION ALL SELECT news_id,0.6 AS weight,'JUDGEMENT',NULL,created_at FROM judgement WHERE user_id=? AND created_at>=? AND created_at<=?
                ) activity GROUP BY news_id ORDER BY weight DESC,news_id LIMIT 500
                """,userId,java.sql.Timestamp.from(since),java.sql.Timestamp.from(now),userId,java.sql.Timestamp.from(since),java.sql.Timestamp.from(now),userId,java.sql.Timestamp.from(since),java.sql.Timestamp.from(now));
        Map<Long,Double> weights=new LinkedHashMap<>();
        Map<Long,Activity> reasons=new HashMap<>();
        activities.forEach(row -> reasons.put(((Number)row.get("news_id")).longValue(),new Activity(Objects.toString(row.get("activity_type"),"READ"),Objects.toString(row.get("term"),""))));
        activities.forEach(row -> weights.put(((Number)row.get("news_id")).longValue(),Math.min(1000,((Number)row.get("weight")).doubleValue())));
        readIds.forEach(id -> weights.merge(id,.3,(left,right)->Math.min(1000,left+right)));
        Set<Long> excluded=new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT news_id FROM learning_progress WHERE user_id=?
                UNION SELECT DISTINCT news_id FROM article_note WHERE user_id=?
                UNION SELECT DISTINCT news_id FROM judgement WHERE user_id=?
                """,Long.class,userId,userId,userId));
        excluded.addAll(readIds); excluded.addAll(weights.keySet());
        Map<Long,News> observations=news.findRecommendationObservations(weights.keySet()).stream().collect(Collectors.toMap(News::getId,article->article));
        var candidates=news.findRecommendationCandidates(since,now,org.springframework.data.domain.PageRequest.of(0,300));
        var index = new SimilarityIndex();
        if (!weights.isEmpty() && excluded.size()<=5000) {
            var request=new AiRecommendationRequest(weights.entrySet().stream().limit(500).map(entry->new AiRecommendationRequest.Behavior(entry.getKey(),entry.getValue())).toList(),candidates.stream().map(News::getId).toList(),excluded.stream().sorted().toList());
            var result=ai.recommendations(request);
            if (result.isPresent() && "LOCAL_SENTENCE_TRANSFORMER".equals(result.get().basis()) && result.get().results()!=null) {
                Map<Long,News> byId=candidates.stream().collect(Collectors.toMap(News::getId,article->article));
                List<Recommendation> selected=new ArrayList<>();
                for (var item:result.get().results()) {
                    if (item==null || item.score()==null || !Double.isFinite(item.score()) || item.score()<=0 || item.score()>1.000001) continue;
                    News article=byId.get(item.newsId()), reason=observations.get(item.reasonNewsId());
                    if (article==null || reason==null || excluded.contains(article.getId()) || duplicate(article,selected,observations.values(),index)) continue;
                    selected.add(view(article,reason,reasons.get(reason.getId()),"LOCAL_SENTENCE_TRANSFORMER",item.score()));
                    if(selected.size()==3) break;
                }
                if(!selected.isEmpty()) return hydrate(prioritize(new Response(selected,"LOCAL_SENTENCE_TRANSFORMER"),candidates,watched,excluded,observations.values(),index));
            }
        }
        record Ranked(News article, News reason, double score) {}
        var ranked=new ArrayList<Ranked>();
        double total=weights.values().stream().mapToDouble(Double::doubleValue).sum();
        for(News article:candidates) {
            if(excluded.contains(article.getId())) continue;
            double score=0,best=0; News reason=null;
            for(var entry:weights.entrySet()) {
                News source=observations.get(entry.getKey()); if(source==null) continue;
                double similarity=index.similarity(article,source);
                score+=entry.getValue()*similarity;
                if(similarity>best) { best=similarity; reason=source; }
            }
            ranked.add(new Ranked(article,reason,total==0?0:score/total));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(row->row.article().getPublishedAt(),Comparator.reverseOrder()).thenComparing(row->row.article().getId()));
        List<Recommendation> selected=new ArrayList<>();
        for(var row:ranked) {
            if(row.reason()==null || duplicate(row.article(),selected,observations.values(),index)) continue;
            selected.add(view(row.article(),row.reason(),reasons.get(row.reason().getId()),"RULE_INTEREST",row.score()));
            if(selected.size()==3) break;
        }
        return hydrate(prioritize(new Response(selected,selected.stream().anyMatch(item->"RULE_INTEREST".equals(item.basis()))?"RULE_INTEREST":"RULE_RECENT"),candidates,watched,excluded,observations.values(),index));
    }
    private Response prioritize(Response original,List<News> candidates,Set<String> watched,Set<Long> excluded,Collection<News> observed, SimilarityIndex index) {
        var eligible=candidates.stream().filter(n->watched.contains(n.getRelatedSymbol()) && !excluded.contains(n.getId())
                && !duplicate(n,List.of(),observed,index)).toList();
        if(eligible.isEmpty()) return original;
        var selected=new ArrayList<Recommendation>();
        var byId=candidates.stream().collect(Collectors.toMap(News::getId,n->n));
        // Preserve existing personalized ranking inside the watchlist group, then fill with recent watched news.
        for(var item:original.items()) if(watched.contains(byId.get(item.newsId()).getRelatedSymbol())) selected.add(item);
        for(var article:eligible) {
            if(selected.size()==3) break;
            if(selected.stream().anyMatch(item->item.newsId().equals(article.getId())) || duplicate(article,selected,observed,index)) continue;
            selected.add(new Recommendation(article.getId(),article.getTitle(),article.getCategory(),"",
                    "관심종목 "+com.finsight.stock.StockCatalog.name(article.getRelatedSymbol())+" 소식이에요",null,null,"WATCHLIST",null));
        }
        for(var item:original.items()) {
            if(selected.size()==3) break;
            if(selected.stream().noneMatch(previous->previous.newsId().equals(item.newsId()) || index.sameEvent(previous.title(),item.title()))) selected.add(item);
        }
        return new Response(List.copyOf(selected),selected.stream().anyMatch(item->"WATCHLIST".equals(item.basis()))?"WATCHLIST":original.basis());
    }
    private Response hydrate(Response response) {
        if (response.items().isEmpty()) return response;
        Map<Long,News> selected=news.findAllById(response.items().stream().map(Recommendation::newsId).toList()).stream()
                .collect(Collectors.toMap(News::getId,n->n));
        return new Response(response.items().stream().map(item -> {
            News article=selected.get(item.newsId());
            String body=article==null?"":extractor.clean(article.getTitle(),article.getRawContent());
            String summary=body==null?"":body.split("(?<=[.!?])\\s+|\\n+",2)[0];
            return new Recommendation(item.newsId(),item.title(),item.category(),summary.substring(0,Math.min(400,summary.length())),item.reason(),item.reasonNewsId(),item.reasonTitle(),item.basis(),item.score());
        }).toList(),response.basis());
    }
    private static final class SimilarityIndex {
        private final Map<String,Set<String>> titles=new HashMap<>();
        private final Map<News,Set<String>> terms=new IdentityHashMap<>();
        private Set<String> title(String text) { return titles.computeIfAbsent(Objects.toString(text,""),LearningRecommendationService::bigrams); }
        private double overlap(Set<String> a,Set<String> b) {
            if(a.isEmpty() && b.isEmpty()) return 0;
            long common=a.stream().filter(b::contains).count();
            return (double)common/(a.size()+b.size()-common);
        }
        boolean sameEvent(String a,String b) { return overlap(title(a),title(b))>=.8; }
        private double termOverlap(Set<String> a,Set<String> b) {
            var union=new HashSet<>(a);union.addAll(b);
            double total=union.stream().mapToDouble(com.finsight.term.KeyTermSelector::recommendationWeight).sum();
            double shared=a.stream().filter(b::contains).mapToDouble(com.finsight.term.KeyTermSelector::recommendationWeight).sum();
            return shared/Math.max(1,total);
        }
        double similarity(News a,News b) {
            return .65*termOverlap(terms.computeIfAbsent(a,n->new HashSet<>(n.getKeyTerms().stream().filter(com.finsight.term.KeyTermSelector::isRecommendationTerm).toList())),terms.computeIfAbsent(b,n->new HashSet<>(n.getKeyTerms().stream().filter(com.finsight.term.KeyTermSelector::isRecommendationTerm).toList())))
                    +(a.getRelatedSymbol()!=null && a.getRelatedSymbol().equals(b.getRelatedSymbol())?.2:0)+.15*overlap(title(a.getTitle()),title(b.getTitle()));
        }
    }
    private Recommendation view(News article, News reason, Activity activity, String basis, double score) {
        return new Recommendation(article.getId(),article.getTitle(),article.getCategory(),"",
                reasonText(reason,activity),reason.getId(),reason.getTitle(),basis,score);
    }
    static String reasonText(News reason, Activity activity) {
        String title=Objects.toString(reason.getTitle(),"");
        if(title.length()>15) title=title.substring(0,15)+"…";
        return switch(activity==null?"READ":activity.type()) {
            case "QUIZ" -> "'"+activity.term()+"' 퀴즈를 틀린 기사와 비슷해요";
            case "MEMO" -> "메모한 '"+title+"'와 비슷해요";
            case "JUDGEMENT" -> "판단한 '"+title+"'와 비슷해요";
            default -> "읽은 '"+title+"'와 비슷해요";
        };
    }
    private boolean duplicate(News article,List<Recommendation> selected,Collection<News> observed, SimilarityIndex index) {
        return selected.stream().anyMatch(previous->index.sameEvent(article.getTitle(),previous.title())) || observed.stream().anyMatch(previous->index.sameEvent(article.getTitle(),previous.getTitle()));
    }
    static boolean sameEvent(String left,String right) {
        Set<String> a=bigrams(left),b=bigrams(right); if(a.isEmpty() || b.isEmpty()) return false;
        Set<String> shared=new HashSet<>(a); shared.retainAll(b); Set<String> union=new HashSet<>(a); union.addAll(b);
        return (double)shared.size()/union.size()>=.8;
    }
    private static Set<String> bigrams(String title) {
        String value=Objects.toString(title,"").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]",""); Set<String> result=new HashSet<>();
        for(int i=0;i+1<value.length();i++) result.add(value.substring(i,i+2)); return result;
    }
    static double similarity(News left,News right) {
        return new SimilarityIndex().similarity(left,right);
    }
}
