package com.finsight.external;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
public record AiDocentRequest(String symbol, @JsonProperty("symbol_name") String symbolName, String date,
        @JsonProperty("change_percent") double changePercent, String period, List<Article> news, String level,
        @JsonProperty("index_name") String indexName, @JsonProperty("index_change_percent") Double indexChangePercent) {
    public record Article(@JsonProperty("news_id") Long newsId, String title, String summary,
                          @JsonProperty("published_at") Instant publishedAt, String relation, String source,
                          @JsonProperty("sentiment_hint") String sentimentHint, @JsonProperty("sentiment_confidence") Double sentimentConfidence) {
        public Article(Long id,String title,String summary,Instant at,String relation,String source) { this(id,title,summary,at,relation,source,null,null); }
    }
}
