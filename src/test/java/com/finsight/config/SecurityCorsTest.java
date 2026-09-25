package com.finsight.config;

import com.finsight.auth.JwtService;
import com.finsight.market.MarketController;
import com.finsight.market.MarketSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketController.class)
@Import({SecurityConfig.class, WebConfig.class, JwtAuthenticationFilter.class})
class SecurityCorsTest {

    private static final String PHONE_WEB_ORIGIN = "http://172.30.1.47:8082";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private MarketSummaryService marketSummaryService;

    @Test
    void allowsLanBrowserPreflightForAuthenticatedMarketRequest() throws Exception {
        mockMvc.perform(options("/api/market/summary")
                        .header(HttpHeaders.ORIGIN, PHONE_WEB_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PHONE_WEB_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")));
    }
}
