package com.finsight.news;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsRepository extends JpaRepository<News, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT n FROM News n WHERE n.id=:id")
    java.util.Optional<News> lockForJudgement(Long id);

    @org.springframework.data.jpa.repository.Query("SELECT MIN(n.publishedAt) FROM News n WHERE n.source NOT LIKE 'SYNTHETIC%' AND LENGTH(n.rawContent)>0")
    Instant collectionStart();

    List<News> findTop10ByRewriteRetryPendingTrueOrderByPublishedAtDesc();

    boolean existsByUrl(String url);

    List<News> findTop3ByOrderByPublishedAtDesc();

    List<News> findAllByOrderByPublishedAtDesc(Pageable pageable);

    List<News> findTop100ByOrderByCreatedAtDesc();

    List<News> findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(Instant since);

    List<News> findByRelatedSymbolAndPublishedAtBetween(String relatedSymbol, Instant start, Instant end);

    List<News> findByPublishedAtBetween(Instant start, Instant end);

}
