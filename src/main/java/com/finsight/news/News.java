package com.finsight.news;

import com.finsight.briefing.SentimentHint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "news")
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true, length = 1000)
    private String url;

    private String source;

    private Instant publishedAt;

    @Column(columnDefinition = "TEXT")
    private String rawContent;

    @Column(columnDefinition = "TEXT")
    private String rewrittenBeginner;

    @Column(columnDefinition = "TEXT")
    private String rewrittenNormal;

    @Column(columnDefinition = "TEXT")
    private String rewrittenAnalyst;

    @Column(columnDefinition = "TEXT")
    private String importanceReason;

    private String relatedSymbol;

    @Enumerated(EnumType.STRING)
    private SentimentHint sentimentHint;

    private String category;

    @Column(nullable = false)
    private Instant createdAt;

    protected News() {
        // JPA
    }

    public News(String title, String url, String source, Instant publishedAt, String rawContent) {
        this.title = title;
        this.url = url;
        this.source = source;
        this.publishedAt = publishedAt;
        this.rawContent = rawContent;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getRewrittenBeginner() {
        return rewrittenBeginner;
    }

    public void setRewrittenBeginner(String rewrittenBeginner) {
        this.rewrittenBeginner = rewrittenBeginner;
    }

    public String getRewrittenNormal() {
        return rewrittenNormal;
    }

    public void setRewrittenNormal(String rewrittenNormal) {
        this.rewrittenNormal = rewrittenNormal;
    }

    public String getRewrittenAnalyst() {
        return rewrittenAnalyst;
    }

    public void setRewrittenAnalyst(String rewrittenAnalyst) {
        this.rewrittenAnalyst = rewrittenAnalyst;
    }

    public String getImportanceReason() {
        return importanceReason;
    }

    public void setImportanceReason(String importanceReason) {
        this.importanceReason = importanceReason;
    }

    public String getRelatedSymbol() {
        return relatedSymbol;
    }

    public void setRelatedSymbol(String relatedSymbol) {
        this.relatedSymbol = relatedSymbol;
    }

    public SentimentHint getSentimentHint() {
        return sentimentHint;
    }

    public void setSentimentHint(SentimentHint sentimentHint) {
        this.sentimentHint = sentimentHint;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
