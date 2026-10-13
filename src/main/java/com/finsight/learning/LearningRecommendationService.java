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
        Map<Long,News> observations=news.findAllById(weights.keySet()).stream().collect(Collectors.toMap(News::getId,article->article));
        var candidates=news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(since).stream()
                .filter(article -> article.getPublishedAt()!=null && !article.getPublishedAt().isAfter(now)
                        && !Objects.toString(article.getSource(),"").startsWith("SYNTHETIC")
                        && !Objects.toString(article.getUrl(),"").contains("example.invalid")
                        && article.getRawContent()!=null && !article.getRawContent().isBlank())
                .limit(300).toList();
        if (!weights.isEmpty() && excluded.size()<=5000) {
            var request=new AiRecommendationRequest(weights.entrySet().stream().limit(500).map(entry->new AiRecommendationRequest.Behavior(entry.getKey(),entry.getValue())).toList(),candidates.stream().map(News::getId).toList(),excluded.stream().sorted().toList());
            var result=ai.recommendations(request);
            if (result.isPresent() && "LOCAL_SENTENCE_TRANSFORMER".equals(result.get().basis()) && result.get().results()!=null) {
                Map<Long,News> byId=candidates.stream().collect(Collectors.toMap(News::getId,article->article));
                List<Recommendation> selected=new ArrayList<>();
                for (var item:result.get().results()) {
                    if (item==null || item.score()==null || !Double.isFinite(item.score()) || item.score()<=0 || item.score()>1.000001) continue;
                    News article=byId.get(item.newsId()), reason=observations.get(item.reasonNewsId());
                    if (article==null || reason==null || excluded.contains(article.getId()) || duplicate(article,selected,observations.values())) continue;
                    selected.add(view(article,reason,reasons.get(reason.getId()),"LOCAL_SENTENCE_TRANSFORMER",item.score()));
                    if(selected.size()==3) break;
                }
                if(!selected.isEmpty()) return new Response(selected,"LOCAL_SENTENCE_TRANSFORMER");
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
                double similarity=similarity(article,source);
                score+=entry.getValue()*similarity;
                if(similarity>best) { best=similarity; reason=source; }
            }
            ranked.add(new Ranked(article,reason,total==0?0:score/total));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(row->row.article().getPublishedAt(),Comparator.reverseOrder()).thenComparing(row->row.article().getId()));
        List<Recommendation> selected=new ArrayList<>();
        for(var row:ranked) {
            if(row.reason()==null || duplicate(row.article(),selected,observations.values())) continue;
            selected.add(view(row.article(),row.reason(),reasons.get(row.reason().getId()),"RULE_INTEREST",row.score()));
            if(selected.size()==3) break;
        }
        return new Response(selected,selected.stream().anyMatch(item->"RULE_INTEREST".equals(item.basis()))?"RULE_INTEREST":"RULE_RECENT");
    }
    private Recommendation view(News article, News reason, Activity activity, String basis, double score) {
        String body=extractor.clean(article.getTitle(),article.getRawContent());
        String summary=body==null?"":body.split("(?<=[.!?])\\s+|\\n+",2)[0];
        return new Recommendation(article.getId(),article.getTitle(),article.getCategory(),summary.substring(0,Math.min(400,summary.length())),
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
    private boolean duplicate(News article,List<Recommendation> selected,Collection<News> observed) {
        return selected.stream().anyMatch(previous->sameEvent(article.getTitle(),previous.title())) || observed.stream().anyMatch(previous->sameEvent(article.getTitle(),previous.getTitle()));
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
        Set<String> a=new HashSet<>(left.getKeyTerms()),b=new HashSet<>(right.getKeyTerms());
        Set<String> shared=new HashSet<>(a);shared.retainAll(b);Set<String> union=new HashSet<>(a);union.addAll(b);
        double terms=union.isEmpty()?0:(double)shared.size()/union.size();
        Set<String> words=bigrams(left.getTitle()),other=bigrams(right.getTitle());
        Set<String> common=new HashSet<>(words);common.retainAll(other);Set<String> all=new HashSet<>(words);all.addAll(other);
        return .65*terms + (left.getRelatedSymbol()!=null && left.getRelatedSymbol().equals(right.getRelatedSymbol())?.2:0) + (all.isEmpty()?0:.15*common.size()/all.size());
    }
}
