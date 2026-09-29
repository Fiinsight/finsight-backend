package com.finsight.localml;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.news.News;
import com.finsight.judgement.Judgement;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LocalRetrievalControllerTest {
    @Test @SuppressWarnings("unchecked") void chartFindsBodyMentionRegardlessOfLegacySingleTicker() throws Exception {
        var requestBody = new AtomicReference<String>();
        var server = server(200, "{\"status\":\"MODEL\",\"results\":[{\"id\":\"1\"}]}", requestBody);
        try {
            var em=mock(EntityManager.class);
            TypedQuery<News> q=mock(TypedQuery.class,RETURNS_SELF);
            when(em.createQuery(contains("select n from News"),eq(News.class))).thenReturn(q);
            News n=mock(News.class); when(n.getId()).thenReturn(1L); when(n.getTitle()).thenReturn("증시 동향");
            when(n.getRawContent()).thenReturn("삼성전자와 SK 하이닉스 실적 전망. 제보는 카카오톡 okjebo");
            when(n.getRelatedSymbol()).thenReturn("KOSPI");
            when(q.getResultList()).thenReturn(List.of(n));
            var mapper=new ObjectMapper().findAndRegisterModules();
            var controller=new LocalRetrievalController(em,mapper,endpoint(server));
            var request=new LocalRetrievalController.Query("하이닉스 실적","news",null,null,"000660",null);
            var response=controller.search(7L,request);
            assertEquals("MODEL",response.get("status"));
            var sent=mapper.readTree(requestBody.get());
            assertEquals("000660",sent.path("documents").get(0).path("symbol").asText());
            assertFalse(sent.path("documents").get(0).path("body").asText().contains("카카오톡"));
            assertEquals(1,response.get("candidateCount"));
            verify(q,never()).setParameter(eq("symbol"),any());
        } finally {
            server.stop(0);
        }
    }
    private LocalRetrievalController.Query query(String kind) {
        return new LocalRetrievalController.Query("HBM 수요",kind,Instant.parse("2026-09-01T00:00:00Z"),Instant.parse("2026-09-28T00:00:00Z"),null,null);
    }
    @Test void rejectsAnonymousBeforeReadingAnyData() {
        var em=mock(EntityManager.class);
        var c=new LocalRetrievalController(em,new ObjectMapper().findAndRegisterModules(),"http://127.0.0.1:1");
        assertThrows(ResponseStatusException.class,()->c.search(null,query("news")));
        verifyNoInteractions(em);
    }
    @Test @SuppressWarnings("unchecked") void casesAlwaysUseAuthenticatedOwnerAndTimeWindow() {
        var em=mock(EntityManager.class);
        TypedQuery<Judgement> q=mock(TypedQuery.class,RETURNS_SELF);
        when(em.createQuery(contains("j.user.id=:owner"),eq(Judgement.class))).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        var c=new LocalRetrievalController(em,new ObjectMapper(),"http://127.0.0.1:1");
        assertEquals("DATA_UNAVAILABLE",c.search(7L,query("case")).get("status"));
        verify(q).setParameter("owner",7L);
        verify(q).setParameter("end",Instant.parse("2026-09-28T00:00:00Z"));
    }
    @Test @SuppressWarnings("unchecked") void rejectsReturnedIdsOutsideAuthorizedCandidates() throws Exception {
        var server = server(200, "{\"status\":\"MODEL\",\"results\":[{\"id\":\"other-user-item\"}]}", new AtomicReference<>());
        try {
            var em=mock(EntityManager.class);
            TypedQuery<News> q=mock(TypedQuery.class,RETURNS_SELF);
            when(em.createQuery(contains("select n from News"),eq(News.class))).thenReturn(q);
            News n=mock(News.class); when(n.getId()).thenReturn(1L); when(n.getTitle()).thenReturn("HBM 공급");
            when(n.getPublishedAt()).thenReturn(Instant.parse("2026-09-02T00:00:00Z"));
            when(q.getResultList()).thenReturn(List.of(n));
            var c=new LocalRetrievalController(em,new ObjectMapper().findAndRegisterModules(),endpoint(server));
            assertEquals("INVALID_RESULT_ID",c.search(7L,query("news")).get("fallbackReason"));
        } finally {
            server.stop(0);
        }
    }
    @Test @SuppressWarnings("unchecked") void httpFailureDoesNotFailExistingApp() throws Exception {
        var server = server(503, "", new AtomicReference<>());
        try {
            var em=mock(EntityManager.class);
            TypedQuery<News> q=mock(TypedQuery.class,RETURNS_SELF);
            when(em.createQuery(contains("select n from News"),eq(News.class))).thenReturn(q);
            News n=mock(News.class); when(n.getId()).thenReturn(1L); when(n.getTitle()).thenReturn("HBM");
            when(q.getResultList()).thenReturn(List.of(n));
            var c=new LocalRetrievalController(em,new ObjectMapper().findAndRegisterModules(),endpoint(server));
            assertEquals("RULE_FALLBACK",c.search(7L,query("news")).get("status"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(int status, String body, AtomicReference<String> requestBody) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
