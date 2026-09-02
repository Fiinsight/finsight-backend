package com.finsight.news;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsRepository extends JpaRepository<News, Long> {

    boolean existsByUrl(String url);

    List<News> findTop3ByOrderByPublishedAtDesc();

    List<News> findByRelatedSymbolAndPublishedAtBetween(String relatedSymbol, Instant start, Instant end);
}
