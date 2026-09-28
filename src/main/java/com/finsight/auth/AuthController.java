package com.finsight.auth;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
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
    @GetMapping("/me")
    public AuthDtos.AuthResponse me(@AuthenticationPrincipal Long userId) { return auth.me(userId); }
    @GetMapping("/kakao/url")
    public AuthDtos.KakaoUrlResponse kakaoUrl(@RequestParam(required = false) String state) {
        return new AuthDtos.KakaoUrlResponse(auth.kakaoUrl(state));
    }
    @GetMapping("/kakao/status")
    public AuthDtos.KakaoConfigStatus kakaoStatus() {
        return auth.kakaoConfigStatus();
    }
    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error,
                                              @RequestParam(name = "error_description", required = false) String errorDescription) {
        if ((code == null || code.isBlank()) && (error == null || error.isBlank())) {
            error = "invalid_callback";
            errorDescription = "카카오 인증 결과가 비어 있습니다.";
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(auth.kakaoCallbackUri(code, state, error, errorDescription)));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
    @PostMapping("/kakao")
    public AuthDtos.AuthResponse kakao(@Valid @RequestBody AuthDtos.KakaoRequest request) { return auth.kakao(request.code()); }
}
