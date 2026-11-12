package com.finsight.external;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
public record AiDocentRequest(String symbol, @JsonProperty("symbol_name") String symbolName, String date,
        @JsonProperty("change_percent") double changePercent, String period, List<Article> news, String level,
        @JsonProperty("index_name") String indexName, @JsonProperty("index_change_percent") Double indexChangePercent,
        @JsonProperty("market_context") String marketContext, List<Term> terms) {
    public record Term(String term,String definition) {}
    public AiDocentRequest { news=List.copyOf(news); terms=terms==null?List.of():List.copyOf(terms); }
    public AiDocentRequest(String symbol,String symbolName,String date,double changePercent,String period,List<Article> news,String level,String indexName,Double indexChangePercent) {
        this(symbol,symbolName,date,changePercent,period,news,level,indexName,indexChangePercent,null,List.of());
    }
    public AiDocentRequest(String symbol,String symbolName,String date,double changePercent,String period,List<Article> news,String level,String indexName,Double indexChangePercent,String marketContext) {
        this(symbol,symbolName,date,changePercent,period,news,level,indexName,indexChangePercent,marketContext,List.of());
    }
    public AiDocentRequest withLevel(String value) {
        return level.equals(value)?this:new AiDocentRequest(symbol,symbolName,date,changePercent,period,news,value,indexName,indexChangePercent,marketContext,terms);
    }
    public record Article(@JsonProperty("news_id") Long newsId, String title, String summary,
                          @JsonProperty("published_at") Instant publishedAt, String relation, String source,
                          @JsonProperty("sentiment_hint") String sentimentHint, @JsonProperty("sentiment_confidence") Double sentimentConfidence,
                          @JsonProperty("after_close_report") boolean afterCloseReport,
                          @JsonProperty("cause_phrase") String causePhrase) {
        public Article(Long id,String title,String summary,Instant at,String relation,String source,String sentiment,Double confidence,boolean afterClose) { this(id,title,summary,at,relation,source,sentiment,confidence,afterClose,null); }
        public Article(Long id,String title,String summary,Instant at,String relation,String source,String sentiment,Double confidence) { this(id,title,summary,at,relation,source,sentiment,confidence,false,null); }
        public Article(Long id,String title,String summary,Instant at,String relation,String source) { this(id,title,summary,at,relation,source,null,null); }
    }
}
