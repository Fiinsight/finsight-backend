package com.finsight.localml;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.news.News;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class BundledRetrievalTest {
    @Test @SuppressWarnings("unchecked")
    void disabledOptionalModelStillServesAuthenticatedSearchThroughBundledAi() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":\"RULE_FALLBACK\",\"results\":[{\"id\":\"1\",\"title\":\"반도체 수출\",\"evidence\":\"수출 증가\",\"score\":1}]}"));
            var em = mock(EntityManager.class);
            TypedQuery<News> query = mock(TypedQuery.class, RETURNS_SELF);
            when(em.createQuery(contains("select n from News"), eq(News.class))).thenReturn(query);
            var article = mock(News.class);
            when(article.getId()).thenReturn(1L);
            when(article.getTitle()).thenReturn("반도체 수출 증가");
            when(article.getRawContent()).thenReturn("반도체 수출이 증가했다.");
            when(article.getUrl()).thenReturn("https://fixture.example/news/1");
            when(article.getPublishedAt()).thenReturn(Instant.parse("2026-10-26T01:00:00Z"));
            when(query.getResultList()).thenReturn(List.of(article));
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                        "finsight.local-ml.enabled", "false", "finsight.ai.base-url", server.url("/").toString())));
                context.registerBean(EntityManager.class, () -> em);
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
                context.register(LocalRetrievalController.class);
                context.refresh();
                var controller = context.getBean(LocalRetrievalController.class);
                var result = controller.search(7L, new LocalRetrievalController.Query("반도체 수출", "news", null, null, null, null));
                assertEquals("RULE_FALLBACK", result.get("status"));
                assertEquals(1, ((List<?>) result.get("results")).size());
                assertEquals("/v1/search", server.takeRequest().getPath());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.search(null, new LocalRetrievalController.Query("반도체 수출", "news", null, null, null, null)));
            }
        }
    }
}
