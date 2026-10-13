package com.finsight.learning;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import org.junit.jupiter.api.Test;

class DemoQuizCatalogTest {
    @Test void catalogReusesActualQuestionsBktAndTradingCalendarWithoutStartingApplication() throws Exception {
        String input="""
            {"terms":[{"term":"금리","short_definition":"이자 비율"},{"term":"환율","short_definition":"통화 교환 비율"},{"term":"물가","short_definition":"상품 가격"}],
            "news":[{"id":1,"title":"금리 변화","url":"https://example.test/1","source":"fixture","published_at":"2026-10-01T00:00:00Z","raw_content":"금리가 2% 상승했다. 중앙은행이 회의를 열었다.","related_symbol":""}],
            "prices":[{"day":"2026-10-08","comparison_date":"2026-10-12"},{"day":"2026-10-08","comparison_date":"2026-10-09"}],
            "attempts":[{"owner":"demo-beginner","term":"금리","level":"beginner","correct":true}]}
            """;
        var previousIn=System.in; var previousOut=System.out;
        var bytes=new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8));
            DemoQuizCatalog.main(new String[0]);
        } finally { System.setIn(previousIn); System.setOut(previousOut); }
        var result=new ObjectMapper().readTree(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).strip().substring(10));
        assertThat(result.path("questions").size()).isEqualTo(3);
        assertThat(result.path("questions").get(0).path("level").asText()).isEqualTo("beginner");
        assertThat(result.path("questions").get(1).path("level").asText()).isEqualTo("normal");
        assertThat(result.path("questions").get(2).path("level").asText()).isEqualTo("analyst");
        assertThat(result.path("prices").size()).isEqualTo(1);
        assertThat(result.path("probabilities").path("demo-beginner:금리").asDouble()).isEqualTo(KnowledgeTracing.update(.3,true,"beginner"));
    }
}
