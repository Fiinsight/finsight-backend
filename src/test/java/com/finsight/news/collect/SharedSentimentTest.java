package com.finsight.news.collect;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SharedSentimentTest {
    @Test void sixtySharedLabelledTitlesHaveTheSameRulesAsPython() throws Exception {
        var cases=new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream("/qa/sentiment-cases.json"));
        int correct=0;
        var classifier=new NewsSentimentClassifier();
        for(var row:cases) if(classifier.classify(row.get("title").asText()).name().equals(row.get("label").asText())) correct++;
        System.out.println("SENTIMENT labelled fixtures correct="+correct+" total="+cases.size());
        assertThat(cases.size()).isEqualTo(60);assertThat(correct).isEqualTo(60);
    }
}
