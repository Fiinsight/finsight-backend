package com.finsight.external;

import static org.assertj.core.api.Assertions.assertThat;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AiInternalAuthenticationTest {
    @Test void missingBackendTokenIsRejectedButCollectionKeepsExplicitExcerptFallback() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setResponseCode(401));
            var client = new AiServiceClient(WebClient.builder(),server.url("/").toString(),"");
            var assembler = new com.finsight.news.collect.NewsAssembler(client,new com.finsight.news.collect.NewsCategoryClassifier(),
                    new com.finsight.news.collect.NewsSymbolMatcher(),new com.finsight.news.collect.NewsSentimentClassifier(),new com.finsight.news.collect.ArticleContentExtractor());
            var now=java.time.Instant.now();
            var article=assembler.assemble(new com.finsight.news.collect.NewsCandidate("삼성전자 매출 증가","https://fixture.test/token","fixture",now,1),"삼성전자의 매출이 늘었다.",false);
            assertThat(server.takeRequest().getHeader("Authorization")).isNull();
            assertThat(article.getRewriteMode()).isEqualTo("EXCERPT");
            assertThat(article.getRawContent()).isEqualTo("삼성전자의 매출이 늘었다.");
            assertThat(article.getRewrittenBeginner()).isNull();
            assertThat(article.getPublishedAt()).isEqualTo(now);
        }
    }
    @Test void backendSendsServiceTokenAndKeepsFallbackOnRejection() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(401));
            var client = new AiServiceClient(WebClient.builder(), server.url("/").toString(), "internal-fixture");
            assertThat(client.rewrite(new AiRewriteRequest("기사", "본문", null, "https://fixture.test/article", false))).isEmpty();
            var request = server.takeRequest();
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer internal-fixture");
            assertThat(request.getPath()).isEqualTo("/ai/news/rewrite");
        }
    }
}
