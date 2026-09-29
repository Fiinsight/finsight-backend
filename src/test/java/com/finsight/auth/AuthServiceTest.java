package com.finsight.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AuthServiceTest {

    @Test
    void allowsConfiguredWebRedirectOnlyByExactMatch() {
        AuthService service = service("http://localhost:8081/auth/kakao");

        assertThat(service.kakaoCallbackUri("code", "http://localhost:8081/auth/kakao", null, null))
                .isEqualTo("http://localhost:8081/auth/kakao?code=code");
        assertThat(service.kakaoCallbackUri("code", "http://localhost:8081/auth/kakao/evil", null, null))
                .isEqualTo("finsight://auth/kakao?code=code");
    }

    @Test
    void keepsExpoRedirectSupportWithoutAllowingHttpUrlsByPrefix() {
        AuthService service = service("exp://127.0.0.1:8081/--/auth/kakao");

        assertThat(service.kakaoCallbackUri("code", "exp://127.0.0.1:8081/--/auth/kakao", null, null))
                .startsWith("exp://127.0.0.1:8081/--/auth/kakao?code=");
        assertThat(service.kakaoCallbackUri("code", "exp://127.0.0.1:8081/--/auth/kakao.evil", null, null))
                .startsWith("finsight://auth/kakao?code=");
        assertThat(service.kakaoCallbackUri("code", "http://localhost:8081/auth/kakao.evil", null, null))
                .startsWith("finsight://auth/kakao?code=");
    }

    private AuthService service(String webRedirectUris) {
        JwtService jwt = new JwtService(
                "VGhpcyBpcyBhIHRlc3Qga2V5IHRoYXQgaXMgbG9uZyBlbm91Z2ggZm9yIEpXVA==", 86400);
        return new AuthService(mock(UserRepository.class), jwt, WebClient.builder(),
                "client", "", "http://localhost:8080/api/auth/kakao/callback",
                "finsight://auth/kakao", webRedirectUris,
                "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me");
    }
}
