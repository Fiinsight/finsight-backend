package com.finsight.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}
    public record SignupRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password, @NotBlank @Size(max = 30) String nickname) {}
    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record KakaoRequest(@NotBlank String code) {}
    public record AuthResponse(String accessToken, Long userId, String email, String nickname) {}
    public record KakaoUrlResponse(String authorizationUrl) {}
    /** Non-secret diagnostics used to verify the active Kakao environment. */
    public record KakaoConfigStatus(boolean configured, String redirectUri, String appRedirectUri) {}
}
