package com.finsight.chart;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ChartRuleParityTest {
 @Test void sharedSyntheticContractMatchesJavaAuthoritativeRules() throws Exception {
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
  var cases=mapper.readTree(getClass().getResourceAsStream("/chart-docent-parity.json")).path("cases");
  assertThat(cases.size()).isGreaterThanOrEqualTo(40);
  for(var item:cases) {
   var request=mapper.treeToValue(item.path("request"),com.finsight.external.AiDocentRequest.class);
   assertThat(mapper.readTree(mapper.writeValueAsBytes(ChartDocentService.rule(request)))).as(item.path("id").asText()).isEqualTo(item.path("expected"));
  }
 }
}
