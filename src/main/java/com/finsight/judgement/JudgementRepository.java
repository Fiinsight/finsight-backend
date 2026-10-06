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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Judgement j SET j.actualDirection=:direction, j.actualChangePercent=:change, j.feedbackText=:feedback, "
        + "j.feedbackGeneratedAt=:generated, j.comparisonDate=:comparison, j.judgementClose=:before, j.comparisonClose=:after "
        + "WHERE j.id=:id AND j.createdAt=:judgedAt AND j.actualDirection IS NULL AND j.feedbackGeneratedAt IS NULL")
    int finalizeOutcome(Long id, Instant judgedAt, String direction, Double change, String feedback, Instant generated,
        java.time.LocalDate comparison, java.math.BigDecimal before, java.math.BigDecimal after);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<Judgement> findFirstByUserAndNewsIdOrderByCreatedAtDescIdDesc(User user, Long newsId);

    // JOIN FETCH pulls `news` in the same query — without it, `open-in-view:
    // false` means the Hibernate session is closed by the time the caller
    // touches the lazy `news` proxy, throwing LazyInitializationException
    // (this is the same class of bug fixed in findAllByOrderByCreatedAtDesc
    // below — FeedbackScheduler.processOne() also calls judgement.getNews()).
    @Query("SELECT j FROM Judgement j JOIN FETCH j.news WHERE j.feedbackGeneratedAt IS NULL AND j.createdAt < :cutoff")
    List<Judgement> findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(Instant cutoff);

    @Query("SELECT j FROM Judgement j JOIN FETCH j.news WHERE j.user.id=:userId AND j.feedbackGeneratedAt IS NULL AND j.createdAt < :cutoff")
    List<Judgement> findPendingByOwner(Long userId, Instant cutoff);

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
    @Query("SELECT j FROM Judgement j JOIN FETCH j.news WHERE j.user = :user AND j.createdAt >= :from AND j.createdAt < :to ORDER BY j.createdAt DESC, j.id DESC")
    List<Judgement> findInRange(User user, Instant from, Instant to);

    @Query("SELECT MAX(j.createdAt) FROM Judgement j WHERE j.user = :user AND j.createdAt < :from")
    Instant previousRecord(User user, Instant from);
}
