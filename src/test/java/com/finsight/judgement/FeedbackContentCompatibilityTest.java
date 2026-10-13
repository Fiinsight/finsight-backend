package com.finsight.judgement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class FeedbackContentCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void structuredObjectKeepsFields() throws Exception {
        var value=json.readValue("{\"verdict\":\"PARTIAL\",\"reasons\":[{\"title\":\"기사\",\"detail\":\"원문 사실\"}],\"nextTip\":\"다음 근거\"}",Judgement.FeedbackContent.class);
        assertThat(value.verdict()).isEqualTo("PARTIAL");
        assertThat(value.reasons().getFirst().detail()).isEqualTo("원문 사실");
        assertThat(value.nextTip()).isEqualTo("다음 근거");
    }
    @Test void stringArrayBecomesReasonsWithoutInventingVerdict() throws Exception {
        var value=json.readValue("[\"원문 사실\",\"시장 자료\"]",Judgement.FeedbackContent.class);
        assertThat(value.verdict()).isNull(); assertThat(value.nextTip()).isNull();
        assertThat(value.reasons()).extracting(reason -> reason.title()).containsExactly("근거","근거");
        assertThat(value.reasons()).extracting(reason -> reason.detail()).containsExactly("원문 사실","시장 자료");
    }
    @Test void brokenValuesDoNotThrowAndLogIdOnlyOnce() throws Exception {
        for(String source : java.util.List.of("42","true","\"not JSON\"","[\"text\",3]","{\"reasons\":\"bad\"}","{broken"))
            assertThat(json.readValue(source,Judgement.FeedbackContent.class)).isNull();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(Judgement.class);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        try {
            var row=new Judgement();
            ReflectionTestUtils.setField(row,"id",123L);
            ReflectionTestUtils.setField(row,"feedbackContent",json.readTree("42"));
            assertThat(row.getFeedbackContent()).isNull(); assertThat(row.getFeedbackContent()).isNull();
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage()).contains("id=123").doesNotContain("42");
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
}
