package com.finsight.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

public final class DailyNoteDtos {
    private DailyNoteDtos() {}

    public record SaveRequest(@NotBlank @Size(max = 280) String content) {}
    public record Response(Long id, LocalDate noteDate, String content, Instant updatedAt) {
        static Response from(DailyNote note) {
            return new Response(note.getId(), note.getNoteDate(), note.getContent(), note.getUpdatedAt());
        }
    }
}
