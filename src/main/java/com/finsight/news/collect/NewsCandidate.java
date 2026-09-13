package com.finsight.news.collect;

import java.time.Instant;

/** An RSS feed entry considered for collection, before/after scoring. */
public record NewsCandidate(String title, String url, String source, Instant publishedAt, int score) {

    public NewsCandidate withScore(int newScore) {
        return new NewsCandidate(title, url, source, publishedAt, newScore);
    }
}
