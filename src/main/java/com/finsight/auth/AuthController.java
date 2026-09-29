package com.finsight.auth;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter rateLimiter;
    public AuthController(AuthService auth, AuthRateLimiter rateLimiter) { this.auth = auth; this.rateLimiter = rateLimiter; }
    @PostMapping("/signup") @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.AuthResponse signup(@Valid @RequestBody AuthDtos.SignupRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check("signup", request.email(), httpRequest.getRemoteAddr());
        return auth.signup(request);
    }
    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check("login", request.email(), httpRequest.getRemoteAddr());
        return auth.login(request);
    }
    @GetMapping("/kakao/url")
    public AuthDtos.KakaoUrlResponse kakaoUrl(@RequestParam(required = false) String state) {
        return new AuthDtos.KakaoUrlResponse(auth.kakaoUrl(state));
    }
    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(@RequestParam String code, @RequestParam(required = false) String state) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(auth.kakaoCallbackUri(code, state)));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
    @PostMapping("/kakao")
    public AuthDtos.AuthResponse kakao(@Valid @RequestBody AuthDtos.KakaoRequest request) { return auth.kakao(request.code()); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleAuthError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("status", exception.getStatusCode().value(), "message", exception.getReason()));
    }
}
