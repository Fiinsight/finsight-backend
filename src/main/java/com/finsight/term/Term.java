package com.finsight.term;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "term")
public class Term {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String term;

    @Column(columnDefinition = "TEXT")
    private String shortDefinition;

    protected Term() {
        // JPA
    }

    public Term(String term, String shortDefinition) {
        this.term = term;
        this.shortDefinition = shortDefinition;
    }

    public Long getId() {
        return id;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getShortDefinition() {
        return shortDefinition;
    }

    public void setShortDefinition(String shortDefinition) {
        this.shortDefinition = shortDefinition;
    }
}
