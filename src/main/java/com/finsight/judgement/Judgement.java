package com.finsight.judgement;

import com.finsight.news.News;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "judgement")
public class Judgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_id", nullable = false)
    private News news;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JudgementChoice choice;

    @Column(columnDefinition = "TEXT")
    private String reasonText;

    @Column(nullable = false)
    private Instant createdAt;

    private String actualDirection;

    private Double actualChangePercent;

    @Column(columnDefinition = "TEXT")
    private String feedbackText;

    private Instant feedbackGeneratedAt;

    protected Judgement() {
        // JPA
    }

    public Judgement(News news, JudgementChoice choice, String reasonText) {
        this.news = news;
        this.choice = choice;
        this.reasonText = reasonText;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public News getNews() {
        return news;
    }

    public JudgementChoice getChoice() {
        return choice;
    }

    public String getReasonText() {
        return reasonText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getActualDirection() {
        return actualDirection;
    }

    public void setActualDirection(String actualDirection) {
        this.actualDirection = actualDirection;
    }

    public Double getActualChangePercent() {
        return actualChangePercent;
    }

    public void setActualChangePercent(Double actualChangePercent) {
        this.actualChangePercent = actualChangePercent;
    }

    public String getFeedbackText() {
        return feedbackText;
    }

    public void setFeedbackText(String feedbackText) {
        this.feedbackText = feedbackText;
    }

    public Instant getFeedbackGeneratedAt() {
        return feedbackGeneratedAt;
    }

    public void setFeedbackGeneratedAt(Instant feedbackGeneratedAt) {
        this.feedbackGeneratedAt = feedbackGeneratedAt;
    }
}
