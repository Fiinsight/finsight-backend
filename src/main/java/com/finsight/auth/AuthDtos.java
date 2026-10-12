package com.finsight.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}
    public record SignupRequest(@Email(message = "이메일 형식이 올바르지 않아요.") @NotBlank(message = "필수 항목을 입력해 주세요.") String email, @NotBlank(message = "필수 항목을 입력해 주세요.") @Size(min = 8, max = 72, message = "비밀번호는 8~72자로 입력해 주세요.") String password, @NotBlank(message = "필수 항목을 입력해 주세요.") @Size(min = 2, max = 30, message = "닉네임은 2~30자로 입력해 주세요.") String nickname) {}
    public record LoginRequest(@Email(message = "이메일 형식이 올바르지 않아요.") @NotBlank(message = "필수 항목을 입력해 주세요.") String email, @NotBlank(message = "필수 항목을 입력해 주세요.") String password) {}
    public record KakaoRequest(@NotBlank(message = "필수 항목을 입력해 주세요.") String code) {}
    public record AuthResponse(String accessToken, Long userId, String email, String nickname) {}
    public record KakaoUrlResponse(String authorizationUrl) {}
    /** Non-secret diagnostics used to verify the active Kakao environment. */
    public record KakaoConfigStatus(boolean configured, String redirectUri, String appRedirectUri) {}
}
