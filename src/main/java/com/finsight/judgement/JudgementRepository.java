package com.finsight.judgement;

import java.time.Instant;
import java.util.List;
import com.finsight.auth.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JudgementRepository extends JpaRepository<Judgement, Long> {

    // JOIN FETCH pulls `news` in the same query — without it, `open-in-view:
    // false` means the Hibernate session is closed by the time the caller
    // touches the lazy `news` proxy, throwing LazyInitializationException
    // (this is the same class of bug fixed in findAllByOrderByCreatedAtDesc
    // below — FeedbackScheduler.processOne() also calls judgement.getNews()).
    @Query("SELECT j FROM Judgement j JOIN FETCH j.news WHERE j.feedbackGeneratedAt IS NULL AND j.createdAt < :cutoff")
    List<Judgement> findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(Instant cutoff);

    // Same as above but without the 1-day-old gate — used by the manual
    // "run now" endpoint so the feedback flow can be tested/demoed without
    // waiting for a real trading day to pass.
    @Query("SELECT j FROM Judgement j JOIN FETCH j.news WHERE j.feedbackGeneratedAt IS NULL")
    List<Judgement> findByFeedbackGeneratedAtIsNull();

    @Query("SELECT j FROM Judgement j JOIN FETCH j.news ORDER BY j.createdAt DESC")
    List<Judgement> findAllByOrderByCreatedAtDesc();

    @Query("SELECT j FROM Judgement j JOIN FETCH j.news WHERE j.user = :user ORDER BY j.createdAt DESC")
    List<Judgement> findAllByUserOrderByCreatedAtDesc(User user);

    @EntityGraph(attributePaths = "news")
    Page<Judgement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "news")
    Page<Judgement> findAllByUserOrderByCreatedAtDesc(User user, Pageable pageable);
}
