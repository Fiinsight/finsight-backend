package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body sent to the AI service's POST /ai/feedback/judgement endpoint
 * (finsight-ai's {@code FeedbackRequest} requires {@code news_id} and
 * {@code user_choice} in {@code UP|NEUTRAL|DOWN}).
 */
public record AiFeedbackRequest(
        @JsonProperty("news_id") Long newsId,
        @JsonProperty("user_choice") String userChoice,
        @JsonProperty("user_reason") String userReason,
        @JsonProperty("market_result") String marketResult,
        @JsonProperty("news_title") String newsTitle,
        @JsonProperty("news_summary") String newsSummary,
        @JsonProperty("related_symbol") String relatedSymbol,
        @JsonProperty("related_symbol_name") String relatedSymbolName,
        @JsonProperty("actual_change_percent") Double actualChangePercent,
        @JsonProperty("judgement_close") java.math.BigDecimal judgementClose,
        @JsonProperty("comparison_close") java.math.BigDecimal comparisonClose,
        @JsonProperty("comparison_date") java.time.LocalDate comparisonDate,
        String level,
        @JsonProperty("index_name") String indexName,
        @JsonProperty("index_change_percent") Double indexChangePercent,
        @JsonProperty("sentiment_hint") String sentimentHint,
        @JsonProperty("sentiment_confidence") Double sentimentConfidence
) {
    public AiFeedbackRequest(Long newsId, String choice, String reason, String direction, String title, String summary,
            String symbol, String name, Double percent, java.math.BigDecimal before, java.math.BigDecimal after, java.time.LocalDate date, String level,
            String indexName, Double indexChangePercent) {
        this(newsId,choice,reason,direction,title,summary,symbol,name,percent,before,after,date,level,indexName,indexChangePercent,null,null);
    }
    public AiFeedbackRequest(Long newsId, String choice, String reason, String direction, String title, String summary,
            String symbol, String name, Double percent, java.math.BigDecimal before, java.math.BigDecimal after, java.time.LocalDate date, String level) {
        this(newsId,choice,reason,direction,title,summary,symbol,name,percent,before,after,date,level,null,null);
    }
    public AiFeedbackRequest(Long newsId, String choice, String reason, String direction, String title, String summary,
            String symbol, String name, Double percent, java.math.BigDecimal before, java.math.BigDecimal after, java.time.LocalDate date) {
        this(newsId,choice,reason,direction,title,summary,symbol,name,percent,before,after,date,"normal");
    }
    public AiFeedbackRequest(Long newsId, String choice, String reason, String direction) {
        this(newsId, choice, reason, direction, "", "", null, null, null, null, null, null, "normal");
    }
}
