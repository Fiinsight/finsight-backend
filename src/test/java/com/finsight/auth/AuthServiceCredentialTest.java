package com.finsight.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceCredentialTest {

    @Test
    void signupStoresOnlyPasswordHashAndRejectsDuplicateEmail() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(users.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(jwt.issue(org.mockito.ArgumentMatchers.any(User.class))).thenReturn("token");
        AuthService auth = auth(users, jwt);

        AuthDtos.AuthResponse response = auth.signup(
                new AuthDtos.SignupRequest("user@example.com", "strong-pass-123", "FinSighter"));

        assertEquals("user@example.com", response.email());
        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(users).save(saved.capture());
        assertNotEquals("strong-pass-123", saved.getValue().getPasswordHash());
        assertTrue(new BCryptPasswordEncoder().matches("strong-pass-123", saved.getValue().getPasswordHash()));

        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(saved.getValue()));
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> auth.signup(new AuthDtos.SignupRequest("user@example.com", "strong-pass-123", "Other")));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
    }

    @Test
    void loginRequiresLocalProviderAndCorrectPassword() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        User local = new User("user@example.com", new BCryptPasswordEncoder().encode("correct-pass"),
                "Local", AuthProvider.LOCAL);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(local));
        when(jwt.issue(local)).thenReturn("token");
        AuthService auth = auth(users, jwt);

        assertEquals("token", auth.login(new AuthDtos.LoginRequest("user@example.com", "correct-pass")).accessToken());
        ResponseStatusException wrongPassword = assertThrows(ResponseStatusException.class,
                () -> auth.login(new AuthDtos.LoginRequest("user@example.com", "incorrect-pass")));
        assertEquals(HttpStatus.UNAUTHORIZED, wrongPassword.getStatusCode());
    }

    private AuthService auth(UserRepository users, JwtService jwt) {
        return new AuthService(users, jwt, WebClient.builder(), "client-id", "", "https://app.test/callback",
                "finsight://auth/kakao", "http://localhost:8082/auth/kakao",
                "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me");
    }
}
