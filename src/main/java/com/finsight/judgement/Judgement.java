package com.finsight.judgement;

import com.finsight.news.News;
import com.finsight.auth.User;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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

    @Column(name = "feedback_headline")
    private String feedbackHeadline;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "feedback_reasons", columnDefinition = "jsonb")
    private FeedbackContent feedbackContent;
    public record FeedbackContent(String verdict, java.util.List<com.finsight.external.AiFeedbackResponse.Reason> reasons, String nextTip) {}
    public String getFeedbackHeadline() { return feedbackHeadline; }
    public FeedbackContent getFeedbackContent() { return feedbackContent; }
    public void setStructuredFeedback(String headline, FeedbackContent content) { feedbackHeadline = headline; feedbackContent = content; }

    private Instant feedbackGeneratedAt;
    private java.time.LocalDate comparisonDate;
    @Column(precision = 24, scale = 8)
    private java.math.BigDecimal judgementClose;
    @Column(precision = 24, scale = 8)
    private java.math.BigDecimal comparisonClose;

    public java.time.LocalDate getComparisonDate() { return comparisonDate; }
    public java.math.BigDecimal getJudgementClose() { return judgementClose; }
    public java.math.BigDecimal getComparisonClose() { return comparisonClose; }
    public void setClosingComparison(java.time.LocalDate date, double before, double after) {
        comparisonDate = date;
        judgementClose = java.math.BigDecimal.valueOf(before);
        comparisonClose = java.math.BigDecimal.valueOf(after);
    }
    public void revise(JudgementChoice choice, String reason) {
        this.choice = choice;
        this.reasonText = reason;
        this.createdAt = Instant.now();
    }

    protected Judgement() {
        // JPA
    }

    public Judgement(User user, News news, JudgementChoice choice, String reasonText) {
        this.user = user;
        this.news = news;
        this.choice = choice;
        this.reasonText = reasonText;
        this.createdAt = Instant.now();
    }
    public User getUser() { return user; }

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
