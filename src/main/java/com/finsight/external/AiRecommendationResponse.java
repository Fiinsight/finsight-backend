package com.finsight.external;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
public record AiRecommendationResponse(String basis, List<Item> results) {
    public record Item(@JsonProperty("news_id") Long newsId, Double score,
            @JsonProperty("reason_news_id") Long reasonNewsId, @JsonProperty("reason_title") String reasonTitle) {}
}
