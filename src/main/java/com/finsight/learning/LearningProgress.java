package com.finsight.learning;

import com.finsight.auth.User;
import com.finsight.news.News;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "learning_progress", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "news_id", "term"}))
public class LearningProgress {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "news_id") private News news;
    @Column(nullable = false, length = 100) private String term;
    @Column(nullable = false, length = 20) private String level;
    @Column(nullable = false) private boolean correct;
    @Column(nullable = false) private Instant answeredAt;
    protected LearningProgress() {}
    public LearningProgress(User user, News news, String term) { this.user = user; this.news = news; this.term = term; }
    public void answer(String level, boolean correct) { this.level = level; this.correct = correct; this.answeredAt = Instant.now(); }
    public News getNews() { return news; }
    public String getTerm() { return term; }
    public String getLevel() { return level; }
    public boolean isCorrect() { return correct; }
    public Instant getAnsweredAt() { return answeredAt; }
}
