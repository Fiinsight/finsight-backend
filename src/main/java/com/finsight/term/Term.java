package com.finsight.term;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "term")
public class Term {
    public enum Kind { CONCEPT, TOPIC, MARKET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String term;

    @Column(columnDefinition = "TEXT")
    private String shortDefinition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'CONCEPT'")
    private Kind kind = Kind.CONCEPT;

    protected Term() {
        // JPA
    }

    public Term(String term, String shortDefinition) {
        this.term = term;
        this.shortDefinition = shortDefinition;
        this.kind = classify(term);
    }

    public Long getId() {
        return id;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
        this.kind = classify(term);
    }

    public Kind getKind() { return kind; }

    public boolean quizEligible(String level) {
        return kind == Kind.CONCEPT || kind == Kind.MARKET && "beginner".equals(level);
    }

    public static Kind classify(String term) {
        return switch (term) {
            case "반도체", "AI", "HBM", "투자", "수출", "수입", "원화" -> Kind.TOPIC;
            case "코스피", "코스닥", "원달러" -> Kind.MARKET;
            default -> Kind.CONCEPT;
        };
    }

    public String getShortDefinition() {
        return shortDefinition;
    }

    public void setShortDefinition(String shortDefinition) {
        this.shortDefinition = shortDefinition;
    }
}
