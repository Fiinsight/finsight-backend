package com.finsight.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/signup") @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.AuthResponse signup(@Valid @RequestBody AuthDtos.SignupRequest request) { return auth.signup(request); }
    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) { return auth.login(request); }
    @GetMapping("/kakao/url")
    public AuthDtos.KakaoUrlResponse kakaoUrl() { return new AuthDtos.KakaoUrlResponse(auth.kakaoUrl()); }
    @PostMapping("/kakao")
    public AuthDtos.AuthResponse kakao(@Valid @RequestBody AuthDtos.KakaoRequest request) { return auth.kakao(request.code()); }
}
