package com.finsight.external;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record AiFeedbackResponse(String verdict, String headline,
        @JsonAlias({"feedback", "feedbackText"}) String summary, List<Reason> reasons, String nextTip) {
    public record Reason(String title, String detail) {}
    public String feedbackText() { return summary; }
    public AiFeedbackResponse(String feedbackText) { this(null, null, feedbackText, null, null); }
}
