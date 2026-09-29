package com.finsight.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AuthServiceTest {

    @Test
    void allowsConfiguredWebRedirectOnlyByExactMatch() {
        AuthService service = service("http://localhost:8081/auth/kakao");

        assertThat(service.kakaoCallbackUri("code", "http://localhost:8081/auth/kakao"))
                .isEqualTo("http://localhost:8081/auth/kakao?code=code");
        assertThat(service.kakaoCallbackUri("code", "http://localhost:8081/auth/kakao/evil"))
                .isEqualTo("finsight://auth/kakao?code=code");
    }

    @Test
    void keepsExpoRedirectSupportWithoutAllowingHttpUrlsByPrefix() {
        AuthService service = service("");

        assertThat(service.kakaoCallbackUri("code", "exp://127.0.0.1:8081/--/auth/kakao"))
                .startsWith("exp://127.0.0.1:8081/--/auth/kakao?code=");
        assertThat(service.kakaoCallbackUri("code", "http://localhost:8081/auth/kakao.evil"))
                .startsWith("finsight://auth/kakao?code=");
    }

    private AuthService service(String webRedirectUris) {
        JwtService jwt = new JwtService(
                "Zm9yLWxvY2FsLWRldmVsb3BtZW50LXNlY3JldC1jaGFuZ2UtbWU=", 86400);
        return new AuthService(mock(UserRepository.class), jwt, WebClient.builder(),
                "client", "", "http://localhost:8080/api/auth/kakao/callback",
                "finsight://auth/kakao", webRedirectUris);
    }
}
