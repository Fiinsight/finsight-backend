package com.finsight.judgement;

import java.io.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JudgementRegradePlanTest {
    private com.fasterxml.jackson.databind.JsonNode plan(String prices) throws Exception {
        String input = """
          {"judgements":[{"id":1,"user_id":1,"news_id":1,"choice":"DOWN","created_at":"2026-10-06T01:00:00Z","title":"기준금리 동결 발표","raw_content":"기준금리는 기존 수준을 유지했다. 국내 경제의 물가 변화를 살폈다.","url":"https://example.test","source":"fixture"}],"terms":[],"prices":%s}
          """.formatted(prices);
        var beforeIn=System.in; var beforeOut=System.out; var output=new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));System.setOut(new PrintStream(output));
            JudgementRegradePlan.main(new String[0]);
        } finally { System.setIn(beforeIn);System.setOut(beforeOut); }
        return new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8).strip().substring("REGRADE_JSON:".length())).get(0);
    }
    @Test void missingSavedPricesNeverInventAnOutcome() throws Exception {
        var row=plan("[]");assertThat(row.path("target").asText()).isEqualTo("KOSPI");
        assertThat(row.path("status").asText()).isEqualTo("NO_SAVED_CLOSES");assertThat(row.has("actualChangePercent")).isFalse();
        assertThat(row.path("comparisonDate").asText()).isEqualTo("2026-10-07");
    }
    @Test void previewUsesExactSavedNextTradingDayClosesAndRuleFeedback() throws Exception {
        var row=plan("[{\"symbol\":\"KOSPI\",\"day\":\"2026-10-06\",\"close\":100},{\"symbol\":\"KOSPI\",\"day\":\"2026-10-07\",\"close\":98}]");
        assertThat(row.path("status").asText()).isEqualTo("READY");assertThat(row.path("actualChangePercent").asDouble()).isCloseTo(-2,org.assertj.core.data.Offset.offset(.00001));
        assertThat(row.path("feedback").path("reasons").size()).isEqualTo(3);assertThat(row.path("feedback").path("mode").asText()).isEqualTo("RULE");
    }
}
