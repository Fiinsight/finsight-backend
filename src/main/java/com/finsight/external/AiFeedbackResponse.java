package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiFeedbackResponse(String verdict, String headline,
        String summary, List<Reason> reasons, String nextTip,
        String conceptTerm, String conceptDefinition, String mode) {
    @JsonCreator
    public static AiFeedbackResponse fromJson(@JsonProperty("verdict") String verdict,
            @JsonProperty("headline") String headline, @JsonProperty("summary") String summary,
            @JsonProperty("feedback") String feedback, @JsonProperty("feedbackText") String feedbackText,
            @JsonProperty("reasons") List<Reason> reasons, @JsonProperty("nextTip") String nextTip,
            @JsonProperty("conceptTerm") String conceptTerm, @JsonProperty("conceptDefinition") String conceptDefinition,
            @JsonProperty("mode") String mode) {
        return new AiFeedbackResponse(verdict,headline,summary != null ? summary : feedback != null ? feedback : feedbackText,
                reasons,nextTip,conceptTerm,conceptDefinition,mode);
    }
    public record Reason(String title, String detail) {}
    public String feedbackText() { return summary; }
    public AiFeedbackResponse(String verdict, String headline, String summary, List<Reason> reasons, String nextTip) {
        this(verdict,headline,summary,reasons,nextTip,null,null,"RULE");
    }
    public AiFeedbackResponse(String feedbackText) { this(null, null, feedbackText, null, null); }
}
