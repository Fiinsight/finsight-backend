package com.finsight.auth;

import com.finsight.config.ApiExceptionHandler;
import com.finsight.config.JwtAuthenticationFilter;
import com.finsight.config.SecurityConfig;
import com.finsight.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, WebConfig.class, JwtAuthenticationFilter.class, ApiExceptionHandler.class})
class KakaoErrorResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthRateLimiter authRateLimiter;

    @Test void invalidEmailHasStructuredKoreanMessage() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"abc\",\"password\":\"123456789\",\"nickname\":\"사용자\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("이메일 형식이 올바르지 않아요."))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }
    @Test void deleteRequiresAuthenticationAndDeletesOnlySignedInOwner() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/auth/me")).andExpect(status().isForbidden());
        when(jwtService.isValid("owner-token")).thenReturn(true);
        when(jwtService.userId("owner-token")).thenReturn(7L);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/auth/me").header("Authorization", "Bearer owner-token"))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(authService).deleteAccount(7L);
    }
    @Test
    void returnsKakaoProviderErrorInsteadOfMaskingItAsForbidden() throws Exception {
        when(authService.kakao(anyString())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 토큰 발급에 실패했습니다."));

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"test-code\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("카카오 토큰 발급에 실패했습니다."));
    }
}
