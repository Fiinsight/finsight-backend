package com.finsight.external;

/**
 * Request body sent to the AI service's POST /ai/news/rewrite endpoint.
 * The AI service rewrites one reading level per call (see {@code RewriteRequest}
 * in finsight-ai/app/routers/news.py), so the caller issues this once per level.
 */
public record AiRewriteRequest(String title, String body, String level) {
}
