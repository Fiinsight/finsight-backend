package com.finsight.judgement;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class FeedbackModeTest {
 @Test void modeRoundTripsButLegacyDoesNotPretendToBeAi() throws Exception {
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  var content=new Judgement.FeedbackContent("CORRECT",java.util.List.of(),"tip",null,null,null,null,"LLM");
  assertThat(json.treeToValue(Judgement.feedbackJson(content),Judgement.FeedbackContent.class).mode()).isEqualTo("LLM");
  assertThat(json.readValue("{\"verdict\":\"CORRECT\"}",Judgement.FeedbackContent.class).mode()).isNull();
 }
}
