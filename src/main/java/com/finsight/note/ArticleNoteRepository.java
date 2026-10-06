package com.finsight.note;

import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleNoteRepository extends JpaRepository<ArticleNote, Long> {
    List<ArticleNote> findTop50ByUser_IdOrderByUpdatedAtDesc(Long userId);
    List<ArticleNote> findAllByNews_IdAndUser_IdOrderByUpdatedAtDesc(Long newsId, Long userId);
    Optional<ArticleNote> findByIdAndUser_Id(Long id, Long userId);
    @Query("SELECT n FROM ArticleNote n JOIN FETCH n.news WHERE n.user.id = :userId AND n.createdAt >= :from AND n.createdAt < :to ORDER BY n.createdAt DESC, n.id DESC")
    List<ArticleNote> findInRange(Long userId, Instant from, Instant to);

    @Query("SELECT MAX(n.createdAt) FROM ArticleNote n WHERE n.user.id = :userId AND n.createdAt < :from")
    Instant previousRecord(Long userId, Instant from);
}
