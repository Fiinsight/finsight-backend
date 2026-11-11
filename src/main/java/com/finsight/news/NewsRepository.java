package com.finsight.news;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsRepository extends JpaRepository<News, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT new com.finsight.news.News(n.id,n.title,n.url,n.source,n.publishedAt,n.category,n.relatedSymbol,n.keyTerms) FROM News n WHERE n.publishedAt>=:since AND n.publishedAt<=:now AND coalesce(n.source,'') NOT LIKE 'SYNTHETIC%' AND n.url NOT LIKE '%example.invalid%' AND length(trim(n.rawContent))>0 ORDER BY n.publishedAt DESC,n.id DESC")
    List<News> findRecommendationCandidates(Instant since, Instant now, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT new com.finsight.news.News(n.id,n.title,n.url,n.source,n.publishedAt,n.category,n.relatedSymbol,n.keyTerms) FROM News n WHERE n.id IN :ids")
    List<News> findRecommendationObservations(java.util.Collection<Long> ids);

    @org.springframework.data.jpa.repository.Query("SELECT new com.finsight.news.News(n.id,n.title,n.url,n.source,n.publishedAt,n.category,n.relatedSymbol,n.keyTerms,substring(trim(n.rawContent),1,300)) FROM News n WHERE n.publishedAt BETWEEN :start AND :end AND n.id>:afterId ORDER BY n.id")
    List<News> findChartCandidatesAfterId(Instant start, Instant end, Long afterId, Pageable pageable);

    long countByCreatedAtBetween(Instant since, Instant until);
    long countByPublishedAtBetween(Instant since, Instant until);
    @org.springframework.data.jpa.repository.Query("""
            select n from News n where n.publishedAt>=:since and n.publishedAt<=:now
            and exists (select w.id from WatchlistEntry w where w.userId=:userId and w.symbol=n.relatedSymbol)
            order by n.publishedAt desc, n.id desc
            """)
    List<News> findWatchlistNews(Long userId, Instant since, Instant now, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT n FROM News n WHERE n.id=:id")
    java.util.Optional<News> lockForJudgement(Long id);

    @org.springframework.data.jpa.repository.Query("SELECT MIN(n.publishedAt) FROM News n WHERE n.source NOT LIKE 'SYNTHETIC%' AND LENGTH(n.rawContent)>0")
    Instant collectionStart();

    List<News> findTop10ByRewriteRetryPendingTrueOrderByPublishedAtDesc();

    boolean existsByUrl(String url);

    @org.springframework.data.jpa.repository.Query("select n.url from News n where n.url in :urls")
    List<String> findUrlsIn(java.util.Collection<String> urls);

    List<News> findTop3ByOrderByPublishedAtDesc();

    List<News> findAllByOrderByPublishedAtDesc(Pageable pageable);

    List<News> findByPublishedAtBetweenOrderByPublishedAtDesc(Instant since, Instant until, Pageable pageable);

    List<News> findTop100ByOrderByCreatedAtDesc();

    List<News> findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(Instant since);
    List<News> findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(Instant since, Pageable pageable);

    List<News> findByRelatedSymbolAndPublishedAtBetween(String relatedSymbol, Instant start, Instant end);

    List<News> findByPublishedAtBetween(Instant start, Instant end);

}
