package com.finsight.localml;

import com.finsight.localml.LocalRetrievalController.Doc;
import com.finsight.localml.LocalRetrievalController.Query;
import com.finsight.term.Term;
import com.finsight.judgement.Judgement;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LocalRetrievalCandidates {
    private final EntityManager em;
    public LocalRetrievalCandidates(EntityManager em) { this.em = em; }
    public record NewsCandidate(Long id, String title, String rawContent, Instant publishedAt, String relatedSymbol, String url, String source) {
        public Long getId() { return id; }
        public String getTitle() { return title; }
        public String getRawContent() { return rawContent; }
        public Instant getPublishedAt() { return publishedAt; }
        public String getRelatedSymbol() { return relatedSymbol; }
        public String getUrl() { return url; }
        public String getSource() { return source; }
    }
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Doc> candidates(Query q, Long userId, Instant start, Instant end) {
        if ("term".equals(q.kind())) {
            var query = em.createQuery("select t from Term t" + (q.term() == null ? "" : " where lower(t.term)=lower(:term)") + " order by t.id", Term.class);
            if (q.term() != null) query.setParameter("term", q.term());
            return query.setHint("jakarta.persistence.query.timeout", 2500).setMaxResults(200).getResultList().stream().map(t -> new Doc(t.getId().toString(), "term", t.getTerm(), safe(t.getShortDefinition()), null, null, null, null, "EXISTING_TERM_DICTIONARY", false)).toList();
        }
        if ("case".equals(q.kind())) {
            var query = em.createQuery("select j from Judgement j join fetch j.news n where j.user.id=:owner and j.createdAt>=:start and j.createdAt<:end" +
                    (q.symbol() == null ? "" : " and n.relatedSymbol=:symbol") + " order by j.createdAt desc", Judgement.class)
                .setParameter("owner", userId).setParameter("start", start).setParameter("end", end);
            if (q.symbol() != null) query.setParameter("symbol", q.symbol());
            return query.setHint("jakarta.persistence.query.timeout", 2500).setMaxResults(200).getResultList().stream().map(j -> new Doc(j.getId().toString(), "case", j.getNews().getTitle(),
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
        // Match query words before the bound, rather than truncating all news to the newest 200.
        List<String> keywords = company == null && q.symbol() == null
                ? Arrays.stream(q.query().toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣]+"))
                    .filter(word -> word.length() >= 2).distinct().limit(12).toList() : List.of();
        if (!keywords.isEmpty()) {
            List<String> predicates = new ArrayList<>();
            for (int i=0; i<keywords.size(); i++)
                predicates.add("(lower(n.title) like :keyword"+i+" or lower(coalesce(n.rawContent,'')) like :keyword"+i+")");
            companyFilter += " and (" + String.join(" or ", predicates) + ")";
        }
        var query = em.createQuery("select new com.finsight.localml.LocalRetrievalCandidates$NewsCandidate(n.id,n.title,substring(n.rawContent,1,8000),n.publishedAt,n.relatedSymbol,n.url,n.source) from News n where (n.source is null or n.source not like 'SYNTHETIC%') and n.url not like '%example.invalid%' and length(trim(coalesce(n.rawContent,''))) > 0 and lower(coalesce(n.rawContent,'')) not like '%google 검색%' and coalesce(n.rawContent,'') not like '%한경 프리미엄9을 구독%' and n.publishedAt>=:start and n.publishedAt<:end" +
                companyFilter + " order by n.publishedAt desc", NewsCandidate.class)
            .setParameter("start", start).setParameter("end", end);
        if (company != null) {
            for(int i=0;i<company.terms().size();i++) query.setParameter("alias"+i,"%"+company.terms().get(i).toLowerCase(Locale.ROOT)+"%");
        } else if (q.symbol() != null) query.setParameter("symbol", q.symbol());
        for (int i=0; i<keywords.size(); i++) query.setParameter("keyword"+i, "%"+keywords.get(i)+"%");
        // Stored article bodies are already cleaned during collection; do not repeat publisher regex work for every candidate.
        return query.setHint("jakarta.persistence.query.timeout", 2500).setMaxResults(company == null ? 200 : 500).getResultList().stream()
            .filter(n -> company == null || ChartCompanyMatcher.reason(q.symbol(),n.getTitle(),n.getRawContent()) != null)
            .limit(company == null ? 200 : 100).map(n -> new Doc(n.getId().toString(), "news", n.getTitle(),
            company == null ? safe(n.getRawContent()) : ChartCompanyMatcher.excerpt(q.symbol(),n.getRawContent(),4000), n.getPublishedAt(), company == null ? n.getRelatedSymbol() : q.symbol(), null, n.getUrl(), n.getSource(),
            n.getSource() != null && n.getSource().startsWith("SYNTHETIC"))).toList();
    }

    private String safe(String text) { return text == null ? "" : text.substring(0, Math.min(20000, text.length())); }
}
