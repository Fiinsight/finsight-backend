package com.finsight.external;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
public record AiRecommendationRequest(List<Behavior> behaviors,
        @JsonProperty("candidate_ids") List<Long> candidateIds, @JsonProperty("excluded_ids") List<Long> excludedIds) {
    public record Behavior(@JsonProperty("news_id") Long newsId, double weight) {}
}
