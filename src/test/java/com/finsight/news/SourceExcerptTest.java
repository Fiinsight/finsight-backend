package com.finsight.news;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class SourceExcerptTest {
 @Test void rawResponseIsAtMostThreeSentencesAndThreeHundredCharacters() {
  assertThat(NewsDetailResponse.sourceExcerpt("매출은 12.5% 증가했다. 비용은 줄었다. 수출이 늘었다. 전문의 다음 문장이다.")).isEqualTo("매출은 12.5% 증가했다. 비용은 줄었다. 수출이 늘었다.");
  var excerpt=NewsDetailResponse.sourceExcerpt("가".repeat(400));
  assertThat(excerpt.codePointCount(0,excerpt.length())).isEqualTo(300);
  assertThat(excerpt).endsWith("…");
 }
}
