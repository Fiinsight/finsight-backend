package com.finsight.judgement;

import com.finsight.auth.JwtService;
import com.finsight.auth.UserRepository;
import com.finsight.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JudgementController.class)
@Import({SecurityConfig.class,WebConfig.class,JwtAuthenticationFilter.class})
class UnknownRegradeSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JudgementService service;
    @MockitoBean FeedbackScheduler scheduler;
    @MockitoBean UserRepository users;
    @MockitoBean JwtService jwt;

    @BeforeEach void clearStartupInvocation() { clearInvocations(scheduler); }

    @Test void anonymousRequestCannotReadPricesOrChangeRecords() throws Exception {
        mvc.perform(post("/api/judgements/regrade-unknown").param("apply","true")).andExpect(status().is4xxClientError());
        verifyNoInteractions(scheduler);
    }
    @Test void tokenOwnerOverridesAnyRequestedUserId() throws Exception {
        when(jwt.isValid("owner-token")).thenReturn(true);when(jwt.userId("owner-token")).thenReturn(7L);
        when(scheduler.regradeUnknown(7L,false)).thenReturn(new JudgementRegradeResponse(true,0,0,java.util.List.of()));
        mvc.perform(post("/api/judgements/regrade-unknown").header("Authorization","Bearer owner-token").param("userId","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
        verify(scheduler).regradeUnknown(7L,false);verifyNoMoreInteractions(scheduler);
    }
}
