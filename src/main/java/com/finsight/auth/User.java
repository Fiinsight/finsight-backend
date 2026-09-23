package com.finsight.auth;

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
@Table(name = "app_user")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String email;
    private String passwordHash;
    private String nickname;
    @Column(length = 100)
    private String providerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private AuthProvider provider;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(columnDefinition = "TEXT")
    private String onboardingAnswersJson;
    @Column(length = 30)
    private String learningLevel;
    @Column(length = 30)
    private String learningPace;
    @Column(length = 30)
    private String learningFocus;
    @Column(length = 100)
    private String dailyGoal;
    private Instant onboardingCompletedAt;

    protected User() {}

    public User(String email, String passwordHash, String nickname, AuthProvider provider) {
        this(email, passwordHash, nickname, provider, null);
    }

    public User(String email, String passwordHash, String nickname, AuthProvider provider, String providerId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.provider = provider;
        this.providerId = providerId;
        this.createdAt = Instant.now();
    }
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getNickname() { return nickname; }
    public AuthProvider getProvider() { return provider; }
    public String getProviderId() { return providerId; }
    public String getOnboardingAnswersJson() { return onboardingAnswersJson; }
    public String getLearningLevel() { return learningLevel; }
    public String getLearningPace() { return learningPace; }
    public String getLearningFocus() { return learningFocus; }
    public String getDailyGoal() { return dailyGoal; }
    public Instant getOnboardingCompletedAt() { return onboardingCompletedAt; }
    public void saveOnboardingProfile(String answersJson, String level, String pace, String focus, String goal, Instant completedAt) {
        this.onboardingAnswersJson = answersJson;
        this.learningLevel = level;
        this.learningPace = pace;
        this.learningFocus = focus;
        this.dailyGoal = goal;
        this.onboardingCompletedAt = completedAt;
    }
}
