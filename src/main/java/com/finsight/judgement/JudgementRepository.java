package com.finsight.judgement;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgementRepository extends JpaRepository<Judgement, Long> {

    List<Judgement> findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(Instant cutoff);

    List<Judgement> findAllByOrderByCreatedAtDesc();
}
