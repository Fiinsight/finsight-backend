package com.finsight.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AuthServiceRedirectTest {
    private final AuthService auth = new AuthService(
            null,
            null,
            WebClient.builder(),
            "client-id",
            "",
            "http://192.168.0.171:8080/api/auth/kakao/callback",
            "finsight://auth/kakao",
            "http://localhost:8081/auth/kakao,http://localhost:8082/auth/kakao,http://127.0.0.1:8081/auth/kakao,http://127.0.0.1:8082/auth/kakao");

    @Test
    void allowsConfiguredLocalWebRedirect() {
        assertEquals(
                "http://localhost:8082/auth/kakao?code=abc",
                auth.kakaoCallbackUri("abc", "http://localhost:8082/auth/kakao", null, null));
    }

    @Test
    void rejectsUntrustedRedirectAndUsesAppScheme() {
        assertEquals(
                "finsight://auth/kakao?code=abc",
                auth.kakaoCallbackUri("abc", "https://attacker.example/auth/kakao", null, null));
    }
}
