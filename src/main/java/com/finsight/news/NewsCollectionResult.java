package com.finsight.news;

/** Summary of one news-collection pipeline run, stage by stage. */
public record NewsCollectionResult(int fetched, int unseen, int topCandidates, int saved) {
}
