package com.finsight.news;

import com.finsight.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NewsCollectionTriggerController.class)
@Import({SecurityConfig.class, WebConfig.class, JwtAuthenticationFilter.class})
class NewsCollectionTriggerSecurityTest {
    private static final String TOKEN = "fixture-collection-token-32-characters";
    @Autowired MockMvc mvc;
    @Autowired NewsCollectionTriggerController controller;
    @MockitoBean NewsCollectionScheduler scheduler;
    @MockitoBean com.finsight.auth.JwtService jwt;

    @BeforeEach void configured() {
        ReflectionTestUtils.setField(controller, "token", TOKEN);
        // ApplicationReadyEvent reaches the mock before the HTTP test starts.
        clearInvocations(scheduler);
    }

    @Test void unconfiguredOrShortTokensFailClosedWith503OnBothAnonymousEndpoints() throws Exception {
        for (int length : new int[]{0, 1, 31}) {
            String shortToken = "x".repeat(length);
            ReflectionTestUtils.setField(controller, "token", shortToken);
            mvc.perform(post("/api/internal/news/collect").header("X-Collection-Token", shortToken)).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/internal/news/status").header("X-Collection-Token", shortToken)).andExpect(status().isServiceUnavailable());
        }
        verifyNoInteractions(scheduler);
    }

    @Test void missingWrongAndOversizedTokensReturn401BeforeSchedulerAccess() throws Exception {
        mvc.perform(post("/api/internal/news/collect")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/news/status")).andExpect(status().isUnauthorized());
        for (String invalid : new String[]{"wrong", "x".repeat(TOKEN.length()), "x".repeat(257)}) {
            mvc.perform(post("/api/internal/news/collect").header("X-Collection-Token", invalid)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/internal/news/status").header("X-Collection-Token", invalid)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(scheduler);
    }

    @Test void exactly32CharactersAreAcceptedWithoutJwtButRetainStartupGuard() throws Exception {
        String boundary = "x".repeat(32);
        ReflectionTestUtils.setField(controller, "token", boundary);
        mvc.perform(get("/api/internal/news/status").header("X-Collection-Token", boundary)).andExpect(status().isOk());
        mvc.perform(post("/api/internal/news/collect").header("X-Collection-Token", boundary))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"));
        verify(scheduler, never()).requestScheduledCatchUp();
        when(scheduler.startupReady()).thenReturn(true);
        when(scheduler.requestScheduledCatchUp()).thenReturn(true);
        mvc.perform(post("/api/internal/news/collect").header("X-Collection-Token", boundary)).andExpect(status().isAccepted());
    }
}
