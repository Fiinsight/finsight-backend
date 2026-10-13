package com.finsight.judgement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.finsight.external.AiFeedbackResponse;
import java.util.ArrayList;

/** Read compatibility only; new writes keep the structured object format. */
public final class FeedbackContentDeserializer extends JsonDeserializer<Judgement.FeedbackContent> {
    @Override public Judgement.FeedbackContent deserialize(JsonParser parser, DeserializationContext context) {
        try {
            JsonNode node = parser.readValueAsTree();
            var reasons = new ArrayList<AiFeedbackResponse.Reason>();
            if (node.isArray()) {
                for (var value : node) {
                    if (!value.isTextual()) return null;
                    reasons.add(new AiFeedbackResponse.Reason("근거", value.textValue()));
                }
                return new Judgement.FeedbackContent(null, java.util.List.copyOf(reasons), null);
            }
            if (!node.isObject() || !textOrNull(node.get("verdict")) || !textOrNull(node.get("nextTip"))) return null;
            var values = node.get("reasons");
            if (values != null && !values.isNull()) {
                if (!values.isArray()) return null;
                for (var value : values) {
                    if (!value.isObject() || !value.path("title").isTextual() || !value.path("detail").isTextual()) return null;
                    reasons.add(new AiFeedbackResponse.Reason(value.get("title").textValue(), value.get("detail").textValue()));
                }
            }
            return new Judgement.FeedbackContent(text(node.get("verdict")), values == null || values.isNull() ? null : java.util.List.copyOf(reasons), text(node.get("nextTip")));
        } catch (Exception invalid) { return null; }
    }
    private static boolean textOrNull(JsonNode value) { return value == null || value.isNull() || value.isTextual(); }
    private static String text(JsonNode value) { return value == null || value.isNull() ? null : value.textValue(); }
}
