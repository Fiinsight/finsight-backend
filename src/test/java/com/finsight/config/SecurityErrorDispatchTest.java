package com.finsight.config;

import jakarta.servlet.DispatcherType;
import com.finsight.market.MarketController;
import com.finsight.market.MarketSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketController.class)
@Import({SecurityConfig.class, WebConfig.class, JwtAuthenticationFilter.class})
class SecurityErrorDispatchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.finsight.auth.JwtService jwtService;

    @MockitoBean
    private MarketSummaryService marketSummaryService;

    @Test
    void errorDispatchIsNotReplacedByAnAuthentication403() throws Exception {
        mockMvc.perform(get("/api/market/summary")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        })
                        .header(HttpHeaders.ORIGIN, "http://localhost:8082"))
                .andExpect(status().isOk());
    }

    @Test
    void normalRequestsToProtectedApiStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/market/summary"))
                .andExpect(status().isForbidden());
    }
}
