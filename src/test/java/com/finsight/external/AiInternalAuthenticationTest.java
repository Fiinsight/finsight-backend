package com.finsight.external;

import static org.assertj.core.api.Assertions.assertThat;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AiInternalAuthenticationTest {
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
