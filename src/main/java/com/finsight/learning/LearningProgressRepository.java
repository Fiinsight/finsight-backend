package com.finsight.learning;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LearningProgressRepository extends JpaRepository<LearningProgress, Long> {
    Optional<LearningProgress> findByUser_IdAndNews_IdAndTermAndQuestionType(Long userId, Long newsId, String term, QuestionType questionType);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "news")
    List<LearningProgress> findByUser_IdOrderByAnsweredAtDesc(Long userId);
}
