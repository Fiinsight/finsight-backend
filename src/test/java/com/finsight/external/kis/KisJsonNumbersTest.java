package com.finsight.external.kis;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class KisJsonNumbersTest {
 @Test void absentBlankMalformedValuesAreNotZeroPercent() throws Exception {
  var json = new ObjectMapper();
  for(var value: new String[]{"{}", "{\"rate\":null}","{\"rate\":\"\"}","{\"rate\":\"invalid\"}"})
   assertTrue(Double.isNaN(KisJsonNumbers.requiredDouble(json.readTree(value), "rate")));
  assertEquals(0, KisJsonNumbers.requiredDouble(json.readTree("{\"rate\":\"0.00\"}"), "rate"));
 }
}
