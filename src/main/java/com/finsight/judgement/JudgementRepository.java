package com.finsight.judgement;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JudgementRepository extends JpaRepository<Judgement, Long> {

    List<Judgement> findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(Instant cutoff);

    // JOIN FETCH pulls `news` in the same query — without it, `open-in-view:
    // false` means the Hibernate session is closed by the time the service
    // layer touches the lazy `news` proxy, throwing LazyInitializationException.
    @Query("SELECT j FROM Judgement j JOIN FETCH j.news ORDER BY j.createdAt DESC")
    List<Judgement> findAllByOrderByCreatedAtDesc();
}
