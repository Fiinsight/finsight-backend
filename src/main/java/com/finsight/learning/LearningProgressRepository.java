package com.finsight.learning;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LearningProgressRepository extends JpaRepository<LearningProgress, Long> {
    Optional<LearningProgress> findByUser_IdAndNews_IdAndTerm(Long userId, Long newsId, String term);
    List<LearningProgress> findTop50ByUser_IdAndCorrectFalseOrderByAnsweredAtDesc(Long userId);
}
