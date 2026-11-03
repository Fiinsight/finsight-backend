package com.finsight.config;

import com.finsight.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LivenessCorsTest.Application.class, properties = {
        "finsight.cors.allowed-origin-patterns=https://finsight-demo.expo.app",
        "management.endpoint.health.probes.enabled=true"
})
@AutoConfigureMockMvc
class LivenessCorsTest {
    private static final String ORIGIN = "https://finsight-demo.expo.app";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
    @Import({SecurityConfig.class, WebConfig.class, JwtAuthenticationFilter.class})
    static class Application {}

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtService jwtService;

    @Test
    void realActuatorLivenessReturnsCorsHeadersWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void realActuatorPreflightAllowsConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/actuator/health/liveness")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test
    void unrelatedOriginCannotReadLiveness() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
