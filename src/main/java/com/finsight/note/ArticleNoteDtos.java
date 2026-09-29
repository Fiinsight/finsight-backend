package com.finsight.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ArticleNoteDtos {
    private ArticleNoteDtos() {}

    public record CreateRequest(@NotNull Long newsId, @NotBlank @Size(max = 1000) String content) {}
    public record SaveRequest(@NotBlank @Size(max = 1000) String content) {}
    public record Response(Long id, Long newsId, String newsTitle, String source, String content,
                           Instant createdAt, Instant updatedAt) {
        static Response from(ArticleNote note) {
            return new Response(note.getId(), note.getNews().getId(), note.getNews().getTitle(),
                    note.getNews().getSource(), note.getContent(), note.getCreatedAt(), note.getUpdatedAt());
        }
    }
}
