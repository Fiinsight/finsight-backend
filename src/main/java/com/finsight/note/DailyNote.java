package com.finsight.note;

import com.finsight.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_note", uniqueConstraints = @UniqueConstraint(name = "uk_daily_note_user_date", columnNames = {"user_id", "note_date"}))
public class DailyNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;

    @Column(nullable = false, length = 280)
    private String content;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DailyNote() {}

    public DailyNote(User user, LocalDate noteDate, String content) {
        this.user = user;
        this.noteDate = noteDate;
        this.content = content;
        this.updatedAt = Instant.now();
    }

    public void updateContent(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public LocalDate getNoteDate() { return noteDate; }
    public String getContent() { return content; }
    public Instant getUpdatedAt() { return updatedAt; }
}
