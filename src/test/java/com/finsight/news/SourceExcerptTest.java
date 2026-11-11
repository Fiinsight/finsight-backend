package com.finsight.news;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class SourceExcerptTest {
 @Test void excerptsKeepWholeSentencesAndParagraphsWithinWiderBudget() {
  assertThat(NewsDetailResponse.sourceExcerpt("매출은 12.5% 증가했다. 비용은 줄었다. 수출이 늘었다. 전문의 다음 문장이다."))
    .isEqualTo("매출은 12.5% 증가했다. 비용은 줄었다. 수출이 늘었다. 전문의 다음 문장이다.");
  assertThat(NewsDetailResponse.sourceExcerpt("가".repeat(400))).hasSize(400);
  String first="가".repeat(700)+".", second="나".repeat(700)+".";
  assertThat(NewsDetailResponse.sourceExcerpt(first+" "+second)).isEqualTo(first);
  assertThat(NewsDetailResponse.sourceExcerpt("매출이 크게 늘었다. 이익도 증가했다.\n\n수출도 증가했다."))
    .isEqualTo("매출이 크게 늘었다. 이익도 증가했다.\n\n수출도 증가했다.");
 }
 @Test void fullContentSettingKeepsCompleteCleanedBody() {
  var news=new News("실적 전망", "https://fixture.test", "fixture", java.time.Instant.now(),
    "홍길동 기자 = 반도체 수출이 크게 늘었다. 영업이익도 증가했다.\n\n다음 분기 수출 전망도 개선됐다.");
  var result=NewsDetailResponse.from(news,new com.finsight.news.collect.NewsCategoryClassifier(),new com.finsight.news.collect.ArticleContentExtractor(),true);
  assertThat(result.fullContentEnabled()).isTrue();
  assertThat(result.rawContent()).doesNotContain("기자").contains("늘었다. 영업이익도 증가했다.\n\n다음 분기");
 }
}
