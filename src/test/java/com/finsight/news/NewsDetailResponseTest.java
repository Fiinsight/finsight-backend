package com.finsight.news;
import static org.junit.jupiter.api.Assertions.*;
import com.finsight.news.collect.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
class NewsDetailResponseTest {
 @Test void oldStoredEverydayTermsDoNotRefillAnIntentionallyEmptyGlossary() {
  var news=new News("주식 시장 기업", "https://fixture.test", "fixture", Instant.EPOCH, "기업의 투자와 수출 소식을 전했다. ".repeat(8));
  news.setKeyTerms(List.of("주식","투자","AI"));
  var result=NewsDetailResponse.from(news,new NewsCategoryClassifier(),new ArticleContentExtractor(),true);
  assertTrue(result.keyTerms().isEmpty());
  assertTrue(result.keyTermsByLevel().values().stream().allMatch(List::isEmpty));
 }
 @Test void missingBodyDoesNotPresentStaleRewriteAsArticleContent() {
  var news = new News("금리 전망", "https://publisher.test/article", "publisher", Instant.EPOCH, "");
  news.setRewrittenNormal("오래된 요약"); news.setKeyTerms(List.of("금리"));
  var result = NewsDetailResponse.from(news, new NewsCategoryClassifier(), new ArticleContentExtractor());
  assertEquals("", result.rawContent()); assertEquals("", result.rewrittenNormal());
  assertEquals("", result.importanceReason()); assertTrue(result.keyTerms().isEmpty());
  assertEquals(news.getUrl(), result.url());
 }
}
