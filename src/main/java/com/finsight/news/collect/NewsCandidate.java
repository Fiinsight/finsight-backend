package com.finsight.news.collect;

import java.time.Instant;

/** An RSS feed entry considered for collection, before/after scoring. */
public record NewsCandidate(String title, String url, String source, Instant publishedAt, int score, String imageUrl) {

    public NewsCandidate { imageUrl = NewsImageUrl.sanitize(imageUrl, url); }
    public NewsCandidate(String title, String url, String source, Instant publishedAt, int score) {
        this(title, url, source, publishedAt, score, null);
    }
    public NewsCandidate withImageUrl(String value) { return new NewsCandidate(title, url, source, publishedAt, score, value); }

    public NewsCandidate withScore(int newScore) {
        return new NewsCandidate(title, url, source, publishedAt, newScore, imageUrl);
    }
}
