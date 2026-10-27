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
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {
        @org.springframework.context.annotation.Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager() {
            return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() { return new Object(); }
                protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {}
                protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            };
        }
    }

    @Test @SuppressWarnings("unchecked")
    void releasesReadTransactionBeforeCallingAdapter() throws Exception {
        try (var server = new MockWebServer(); var context = new AnnotationConfigApplicationContext()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":\"RULE_FALLBACK\",\"results\":[]}"));
            var em = mock(EntityManager.class);
            TypedQuery<LocalRetrievalCandidates.NewsCandidate> query = mock(TypedQuery.class, RETURNS_SELF);
            when(em.createQuery(contains("substring(n.rawContent,1,8000)"), eq(LocalRetrievalCandidates.NewsCandidate.class))).thenReturn(query);
            when(query.getResultList()).thenAnswer(call -> {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                return List.of(new LocalRetrievalCandidates.NewsCandidate(1L,"반도체 수출","반도체 수출이 늘었다.",Instant.now(),null,null,null));
            });
            var mapper = spy(new ObjectMapper().findAndRegisterModules());
            doAnswer(call -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                        "Adapter serialization/wait must not hold a database transaction");
                return call.callRealMethod();
            }).when(mapper).writeValueAsString(any());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                    "finsight.local-ml.base-url", server.url("/").toString())));
            context.registerBean(EntityManager.class, () -> em);
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.register(Transactions.class, LocalRetrievalCandidates.class, LocalRetrievalController.class);
            context.refresh();
            context.getBean(LocalRetrievalController.class).search(7L,
                    new LocalRetrievalController.Query("반도체 수출","news",null,null,null,null));
            assertEquals(1,server.getRequestCount());
            verify(query).setHint("jakarta.persistence.query.timeout",2500);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void disabledOptionalModelStillServesAuthenticatedSearchThroughBundledAi() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":\"RULE_FALLBACK\",\"results\":[{\"id\":\"1\",\"title\":\"반도체 수출\",\"evidence\":\"수출 증가\",\"score\":1}]}"));
            var em = mock(EntityManager.class);
            TypedQuery<LocalRetrievalCandidates.NewsCandidate> query = mock(TypedQuery.class, RETURNS_SELF);
            when(em.createQuery(contains("from News n"), eq(LocalRetrievalCandidates.NewsCandidate.class))).thenReturn(query);
            var article = new LocalRetrievalCandidates.NewsCandidate(1L,"반도체 수출 증가","반도체 수출이 증가했다.",Instant.parse("2026-10-26T01:00:00Z"),null,"https://fixture.example/news/1",null);
            when(query.getResultList()).thenReturn(List.of(article));
            try (var context = new AnnotationConfigApplicationContext()) {
                new org.springframework.boot.env.YamlPropertySourceLoader().load("actual-defaults",
                        new org.springframework.core.io.ClassPathResource("application.yml"))
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                        "finsight.local-ml.enabled", "false", "AI_BASE_URL", server.url("/").toString(), "finsight.ai.base-url", server.url("/").toString())));
                context.registerBean(EntityManager.class, () -> em);
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
                context.register(LocalRetrievalCandidates.class, LocalRetrievalController.class);
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
