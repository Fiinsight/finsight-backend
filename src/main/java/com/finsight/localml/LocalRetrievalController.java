package com.finsight.localml;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.news.News;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.term.Term;
import com.finsight.judgement.Judgement;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Optional adapter; copies into the original repository without editing existing services. */
@RestController
@RequestMapping("/api/local-ml")
@ConditionalOnProperty(name="finsight.local-ml.enabled", havingValue="true")
public class LocalRetrievalController {
    public record Query(@NotBlank @Size(min=2,max=2000) String query,
                        @Pattern(regexp="news|term|case") @NotBlank String kind,
                        Instant startAt, Instant endAt, @Size(max=30) String symbol,
                        @Size(max=100) String term) {}
    public record Doc(String id, String kind, String title, String body, Instant publishedAt,
                      String symbol, String owner, String url, String source, boolean synthetic) {}
    private final ArticleContentExtractor contentExtractor = new ArticleContentExtractor();
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();

    public LocalRetrievalController(EntityManager em, ObjectMapper mapper,
            @Value("${finsight.local-ml.base-url:http://127.0.0.1:8002}") String url) {
        this.em = em; this.mapper = mapper;
        endpoint = URI.create(url.replaceAll("/$", "") + "/v1/search");
    }

    @PostMapping("/search")
    @Transactional(readOnly=true)
    public Map<String,Object> search(@AuthenticationPrincipal Long userId, @Valid @RequestBody Query query) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant end = query.endAt() == null ? Instant.now() : query.endAt();
        Instant start = query.startAt() == null ? end.minus(Duration.ofDays(365)) : query.startAt();
        if (!start.isBefore(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time window");
        List<Doc> docs = candidates(query, userId, start, end);
        if (docs.isEmpty()) return empty("DATA_UNAVAILABLE", "NO_ELIGIBLE_DATA");
        Map<String,Object> request = new LinkedHashMap<>();
        request.put("query", query.query()); request.put("kind", query.kind()); request.put("documents", docs);
        boolean companySearch="news".equals(query.kind()) && ChartCompanyMatcher.get(query.symbol()) != null;
        request.put("topK", companySearch ? 20 : 3); request.put("symbol", query.symbol()); request.put("term", query.term());
        request.put("owner", "case".equals(query.kind()) ? userId.toString() : null);
        request.put("startAt", "term".equals(query.kind()) ? null : start);
        request.put("endAt", "term".equals(query.kind()) ? null : end);
        try {
            var call = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
            var response = http.send(call, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return empty("RULE_FALLBACK", "ADAPTER_HTTP_ERROR");
            Map<String,Object> result = mapper.readValue(response.body(), new TypeReference<>() {});
            if (!List.of("MODEL", "RULE_FALLBACK", "DATA_UNAVAILABLE", "DISABLED").contains(result.get("status")))
                return empty("RULE_FALLBACK", "INVALID_ADAPTER_STATUS");
            if (!(result.get("results") instanceof List<?> rows)) return empty("RULE_FALLBACK", "INVALID_ADAPTER_RESULT");
            Map<String,Doc> authorized = new HashMap<>(); docs.forEach(d -> authorized.put(d.id(), d));
            for (Object row : rows) {
                if (!(row instanceof Map<?,?> map) || !authorized.containsKey(map.get("id")))
                    return empty("RULE_FALLBACK", "INVALID_RESULT_ID");
                @SuppressWarnings("unchecked") Map<String,Object> item = (Map<String,Object>) row;
                Doc doc = authorized.get(item.get("id"));
                item.put("url", doc.url());
                item.put("source", doc.source());
                item.put("publishedAt", doc.publishedAt());
                if (item.get("evidence") instanceof String evidence) {
                    item.put("evidence", evidence.substring(0, Math.min(600, evidence.length())));
                }
            }
            result.put("candidateCount", docs.size());
            if ("news".equals(query.kind()) && ChartCompanyMatcher.get(query.symbol()) != null) {
                Map<String,Doc> byId=new HashMap<>(); docs.forEach(d -> byId.put(d.id(),d));
                for (Object row : rows) {
                    @SuppressWarnings("unchecked") Map<String,Object> item=(Map<String,Object>)row;
                    Doc doc=byId.get(item.get("id"));
                    item.put("matchReason", ChartCompanyMatcher.reason(query.symbol(),doc.title(),doc.body()));
                }
                result.put("results",rows.stream().sorted(Comparator.comparingInt(row -> {
                    var item=(Map<?,?>)row;
                    Doc doc=byId.get(item.get("id"));
                    return ChartCompanyMatcher.reason(query.symbol(),doc.title(),"")==null ? 1 : 0;
                })).limit(3).toList());
                result.put("rankingPolicy","TITLE_MENTION_THEN_SEMANTIC_SCORE");
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return empty("RULE_FALLBACK", "ADAPTER_INTERRUPTED");
        } catch (Exception unavailable) {
            return empty("RULE_FALLBACK", "ADAPTER_UNAVAILABLE");
        }
    }

    private List<Doc> candidates(Query q, Long userId, Instant start, Instant end) {
        if ("term".equals(q.kind())) {
            var query = em.createQuery("select t from Term t" + (q.term() == null ? "" : " where lower(t.term)=lower(:term)") + " order by t.id", Term.class);
            if (q.term() != null) query.setParameter("term", q.term());
            return query.setMaxResults(200).getResultList().stream().map(t -> new Doc(t.getId().toString(), "term", t.getTerm(), safe(t.getShortDefinition()), null, null, null, null, "EXISTING_TERM_DICTIONARY", false)).toList();
        }
        if ("case".equals(q.kind())) {
            var query = em.createQuery("select j from Judgement j join fetch j.news n where j.user.id=:owner and j.createdAt>=:start and j.createdAt<:end" +
                    (q.symbol() == null ? "" : " and n.relatedSymbol=:symbol") + " order by j.createdAt desc", Judgement.class)
                .setParameter("owner", userId).setParameter("start", start).setParameter("end", end);
            if (q.symbol() != null) query.setParameter("symbol", q.symbol());
            return query.setMaxResults(200).getResultList().stream().map(j -> new Doc(j.getId().toString(), "case", j.getNews().getTitle(),
                "사용자 당시 판단: " + j.getChoice() + "\n" + safe(j.getReasonText()), j.getCreatedAt(), j.getNews().getRelatedSymbol(),
                userId.toString(), j.getNews().getUrl(), "OWN_JUDGEMENT_NOT_ACTUAL_RETURN", false)).toList();
        }
        var company = ChartCompanyMatcher.get(q.symbol());
        String companyFilter = q.symbol() == null ? "" : " and n.relatedSymbol=:symbol";
        if (company != null) {
            List<String> predicates=new ArrayList<>();
            for(int i=0;i<company.terms().size();i++) predicates.add("(lower(replace(n.title,' ','')) like :alias"+i+" or lower(replace(n.rawContent,' ','')) like :alias"+i+")");
            companyFilter=" and ("+String.join(" or ",predicates)+")";
        }
        var query = em.createQuery("select n from News n where (n.source is null or n.source not like 'SYNTHETIC%') and n.url not like '%example.invalid%' and lower(coalesce(n.rawContent,'')) not like '%google 검색%' and coalesce(n.rawContent,'') not like '%한경 프리미엄9을 구독%' and n.publishedAt>=:start and n.publishedAt<:end" +
                companyFilter + " order by n.publishedAt desc", News.class)
            .setParameter("start", start).setParameter("end", end);
        if (company != null) {
            for(int i=0;i<company.terms().size();i++) query.setParameter("alias"+i,"%"+company.terms().get(i).toLowerCase(Locale.ROOT)+"%");
        } else if (q.symbol() != null) query.setParameter("symbol", q.symbol());
        return query.setMaxResults(company == null ? 200 : 500).getResultList().stream()
            .filter(n -> company == null || ChartCompanyMatcher.reason(q.symbol(),n.getTitle(),n.getRawContent()) != null)
            .limit(company == null ? 200 : 100).map(n -> new Doc(n.getId().toString(), "news", n.getTitle(),
            company == null ? safe(contentExtractor.clean(n.getTitle(), n.getRawContent())) : ChartCompanyMatcher.excerpt(q.symbol(),contentExtractor.clean(n.getTitle(), n.getRawContent()),4000), n.getPublishedAt(), company == null ? n.getRelatedSymbol() : q.symbol(), null, n.getUrl(), n.getSource(),
            n.getSource() != null && n.getSource().startsWith("SYNTHETIC"))).toList();
    }

    private String safe(String text) { return text == null ? "" : text.substring(0, Math.min(20000, text.length())); }
    private Map<String,Object> empty(String status, String reason) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1"); result.put("status", status); result.put("results", List.of());
        result.put("fallbackReason", reason); result.put("qualityValidated", false);
        result.put("confidence", null); result.put("relationType", "INSUFFICIENT_EVIDENCE");
        result.put("note", "추가 검색 결과가 없습니다. 기존 뉴스·차트 기능을 이용할 수 있습니다.");
        return result;
    }
}
