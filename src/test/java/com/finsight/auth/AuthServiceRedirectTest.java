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
            "http://172.30.1.47:8080/api/auth/kakao/callback",
            "finsight://auth/kakao",
            "http://localhost:8081/auth/kakao,http://localhost:8082/auth/kakao,http://127.0.0.1:8081/auth/kakao,http://127.0.0.1:8082/auth/kakao",
            "https://kauth.kakao.com/oauth/token",
            "https://kapi.kakao.com/v2/user/me");

    @Test
    void allowsConfiguredLocalWebRedirect() {
        assertEquals(
                "http://localhost:8082/auth/kakao?code=abc",
                auth.kakaoCallbackUri("abc", "http://localhost:8082/auth/kakao", null, null));
    }

    @Test
    void allowsConfiguredLanWebRedirect() {
        AuthService lanAuth = new AuthService(
                null,
                null,
                WebClient.builder(),
                "client-id",
                "",
                "http://172.30.1.47:8080/api/auth/kakao/callback",
                "finsight://auth/kakao",
                "http://localhost:8081/auth/kakao,http://localhost:8082/auth/kakao,http://127.0.0.1:8081/auth/kakao,http://127.0.0.1:8082/auth/kakao,http://172.30.1.47:8082/auth/kakao",
                "https://kauth.kakao.com/oauth/token",
                "https://kapi.kakao.com/v2/user/me");

        assertEquals(
                "http://172.30.1.47:8082/auth/kakao?code=abc",
                lanAuth.kakaoCallbackUri("abc", "http://172.30.1.47:8082/auth/kakao", null, null));
    }

    @Test
    void rejectsUntrustedRedirectAndUsesAppScheme() {
        assertEquals(
                "finsight://auth/kakao?code=abc",
                auth.kakaoCallbackUri("abc", "https://attacker.example/auth/kakao", null, null));
    }
}
