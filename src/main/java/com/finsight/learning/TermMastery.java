package com.finsight.learning;

import com.finsight.auth.User;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Schema mapping also supports local ddl-auto=update; updates use the locked JDBC service. */
@Entity
@Table(name="term_mastery")
@IdClass(TermMastery.Key.class)
public class TermMastery {
    @Id @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private User user;
    @Id @Column(length=100) private String term;
    @Column(nullable=false) private double pKnown;
    @Column(nullable=false) private int attempts;
    private Instant lastAnsweredAt;
    protected TermMastery() {}

    public static class Key implements Serializable {
        public Long user;
        public String term;
        public Key() {}
        @Override public boolean equals(Object value) {
            return value instanceof Key key && Objects.equals(user,key.user) && Objects.equals(term,key.term);
        }
        @Override public int hashCode() { return Objects.hash(user,term); }
    }
}
