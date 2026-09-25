package com.finsight.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceKakaoTest {

    @Test
    void exchangesCodeFetchesProfileAndAvoidsLocalEmailCollision() throws Exception {
        try (MockWebServer kakao = new MockWebServer()) {
            kakao.start();
            kakao.enqueue(json("{\"access_token\":\"mock-kakao-token\"}"));
            kakao.enqueue(json("""
                    {"id":98765,"kakao_account":{"email":"same@example.com","profile":{"nickname":"Kakao user"}}}
                    """));

            UserRepository users = mock(UserRepository.class);
            JwtService jwt = mock(JwtService.class);
            when(jwt.issue(any(User.class))).thenReturn("app-jwt");
            when(users.findByProviderAndProviderId(AuthProvider.KAKAO, "98765")).thenReturn(Optional.empty());
            when(users.findByEmail("same@example.com"))
                    .thenReturn(Optional.of(new User("same@example.com", "hash", "Local", AuthProvider.LOCAL)));
            when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AuthService auth = new AuthService(users, jwt, WebClient.builder(), "client-id", "", "https://app.test/callback",
                    "finsight://auth/kakao", "http://localhost:8082/auth/kakao",
                    kakao.url("/oauth/token").toString(), kakao.url("/v2/user/me").toString());

            AuthDtos.AuthResponse response = auth.kakao("one-time-code");

            assertEquals("app-jwt", response.accessToken());
            assertEquals("", response.email());
            assertEquals("Kakao user", response.nickname());
            var tokenRequest = kakao.takeRequest();
            assertEquals("POST", tokenRequest.getMethod());
            String tokenForm = tokenRequest.getBody().readUtf8();
            assertTrue(tokenForm.contains("grant_type=authorization_code"));
            assertTrue(tokenForm.contains("code=one-time-code"));
            var profileRequest = kakao.takeRequest();
            assertEquals("Bearer mock-kakao-token", profileRequest.getHeader("Authorization"));
        }
    }

    @Test
    void persistsConsentedKakaoEmailAndNickname() throws Exception {
        try (MockWebServer kakao = new MockWebServer()) {
            kakao.start();
            kakao.enqueue(json("{\"access_token\":\"mock-kakao-token\"}"));
            kakao.enqueue(json("""
                    {"id":12345,"kakao_account":{"email":"person@example.com","profile":{"nickname":"수빈"}}}
                    """));

            UserRepository users = mock(UserRepository.class);
            JwtService jwt = mock(JwtService.class);
            when(jwt.issue(any(User.class))).thenReturn("app-jwt");
            when(users.findByProviderAndProviderId(AuthProvider.KAKAO, "12345")).thenReturn(Optional.empty());
            when(users.findByEmail("person@example.com")).thenReturn(Optional.empty());
            when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AuthService auth = new AuthService(users, jwt, WebClient.builder(), "client-id", "", "https://app.test/callback",
                    "finsight://auth/kakao", "http://localhost:8082/auth/kakao",
                    kakao.url("/oauth/token").toString(), kakao.url("/v2/user/me").toString());

            AuthDtos.AuthResponse response = auth.kakao("one-time-code");

            assertEquals("person@example.com", response.email());
            assertEquals("수빈", response.nickname());
            org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
            org.mockito.Mockito.verify(users, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            User persisted = saved.getValue();
            assertEquals("person@example.com", persisted.getEmail());
            assertEquals("수빈", persisted.getNickname());
            assertEquals(AuthProvider.KAKAO, persisted.getProvider());
            assertEquals("12345", persisted.getProviderId());
        }
    }

    @Test
    void completesLoginWhenOptionalKakaoContactFieldsAreUnavailable() throws Exception {
        try (MockWebServer kakao = new MockWebServer()) {
            kakao.start();
            kakao.enqueue(json("{\"access_token\":\"mock-kakao-token\"}"));
            kakao.enqueue(json("{\"id\":54321,\"kakao_account\":{}}"));

            UserRepository users = mock(UserRepository.class);
            JwtService jwt = mock(JwtService.class);
            when(jwt.issue(any(User.class))).thenReturn("app-jwt");
            when(users.findByProviderAndProviderId(AuthProvider.KAKAO, "54321")).thenReturn(Optional.empty());
            when(users.findByEmail("kakao-54321@kakao.local")).thenReturn(Optional.empty());
            when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AuthService auth = new AuthService(users, jwt, WebClient.builder(), "client-id", "", "https://app.test/callback",
                    "finsight://auth/kakao", "http://localhost:8082/auth/kakao",
                    kakao.url("/oauth/token").toString(), kakao.url("/v2/user/me").toString());

            AuthDtos.AuthResponse response = auth.kakao("one-time-code");

            assertEquals("app-jwt", response.accessToken());
            assertEquals("", response.email());
            assertEquals("카카오 사용자", response.nickname());
        }
    }

    @Test
    void requestsOnlyNicknameUntilEmailConsentPermissionIsGranted() {
        AuthService auth = new AuthService(mock(UserRepository.class), mock(JwtService.class), WebClient.builder(),
                "client-id", "", "https://app.test/callback", "finsight://auth/kakao", "",
                "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me");

        String authorizationUrl = auth.kakaoUrl(null);

        assertTrue(authorizationUrl.contains("scope=profile_nickname"));
        assertTrue(!authorizationUrl.contains("account_email"));
    }

    @Test
    void kakaoTokenRejectionBecomesActionableGatewayError() throws Exception {
        try (MockWebServer kakao = new MockWebServer()) {
            kakao.start();
            kakao.enqueue(new MockResponse().setResponseCode(401).setBody("invalid_grant"));
            AuthService auth = new AuthService(mock(UserRepository.class), mock(JwtService.class), WebClient.builder(),
                    "client-id", "", "https://app.test/callback", "finsight://auth/kakao", "",
                    kakao.url("/oauth/token").toString(), kakao.url("/v2/user/me").toString());

            ResponseStatusException error = org.junit.jupiter.api.Assertions.assertThrows(
                    ResponseStatusException.class, () -> auth.kakao("expired-code"));

            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
            assertEquals("/oauth/token", kakao.takeRequest().getPath());
        }
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
