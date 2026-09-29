package com.finsight.note;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleNoteRepository extends JpaRepository<ArticleNote, Long> {
    List<ArticleNote> findTop50ByUser_IdOrderByUpdatedAtDesc(Long userId);
    List<ArticleNote> findAllByNews_IdAndUser_IdOrderByUpdatedAtDesc(Long newsId, Long userId);
    Optional<ArticleNote> findByIdAndUser_Id(Long id, Long userId);
}
