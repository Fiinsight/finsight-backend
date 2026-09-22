package com.finsight.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AuthService {
    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String kakaoClientId;
    private final String kakaoClientSecret;
    private final String kakaoRedirectUri;
    private final String kakaoAppRedirectUri;

    public AuthService(UserRepository users, JwtService jwt, WebClient.Builder webClientBuilder,
                       @Value("${finsight.auth.kakao-client-id:}") String kakaoClientId,
                       @Value("${finsight.auth.kakao-client-secret:}") String kakaoClientSecret,
                       @Value("${finsight.auth.kakao-redirect-uri:}") String kakaoRedirectUri,
                       @Value("${finsight.auth.kakao-app-redirect-uri:finsight://auth/kakao}") String kakaoAppRedirectUri) {
        this.users = users; this.jwt = jwt; this.webClient = webClientBuilder.build();
        this.kakaoClientId = kakaoClientId; this.kakaoClientSecret = kakaoClientSecret; this.kakaoRedirectUri = kakaoRedirectUri; this.kakaoAppRedirectUri = kakaoAppRedirectUri;
    }
    public AuthDtos.AuthResponse signup(AuthDtos.SignupRequest request) {
        if (users.findByEmail(request.email()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        User user = users.save(new User(request.email(), passwordEncoder.encode(request.password()), request.nickname(), AuthProvider.LOCAL));
        return response(user);
    }
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        User user = users.findByEmail(request.email()).orElseThrow(() -> unauthorized());
        if (user.getProvider() != AuthProvider.LOCAL || !passwordEncoder.matches(request.password(), user.getPasswordHash())) throw unauthorized();
        return response(user);
    }
    public AuthDtos.AuthResponse kakao(String code) {
        requireKakaoConfig();
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code"); form.add("client_id", kakaoClientId);
        form.add("redirect_uri", kakaoRedirectUri); form.add("code", code);
        if (!kakaoClientSecret.isBlank()) form.add("client_secret", kakaoClientSecret);
        JsonNode token = webClient.post().uri("https://kauth.kakao.com/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form).retrieve().bodyToMono(JsonNode.class).block();
        if (token == null || token.get("access_token") == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 토큰 발급에 실패했습니다.");
        JsonNode profile = webClient.get().uri("https://kapi.kakao.com/v2/user/me").headers(h -> h.setBearerAuth(token.get("access_token").asText()))
                .retrieve().bodyToMono(JsonNode.class).block();
        if (profile == null || profile.get("id") == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보를 가져오지 못했습니다.");
        String kakaoId = profile.get("id").asText();
        JsonNode account = profile.path("kakao_account");
        String email = account.path("email").asText("kakao-" + kakaoId + "@kakao.local");
        String nickname = account.path("profile").path("nickname").asText("카카오 사용자");
        User user = users.findByEmail(email).orElseGet(() -> users.save(new User(email, null, nickname, AuthProvider.KAKAO)));
        return response(user);
    }
    public String kakaoUrl(String state) {
        requireKakaoConfig();
        String url = "https://kauth.kakao.com/oauth/authorize?client_id=" + encode(kakaoClientId)
                + "&redirect_uri=" + encode(kakaoRedirectUri) + "&response_type=code";
        if (state != null && !state.isBlank()) url += "&state=" + encode(state);
        return url;
    }
    public String kakaoAppRedirectUri() { return kakaoAppRedirectUri; }
    public String kakaoCallbackUri(String code, String state) {
        String destination = isAllowedAppRedirect(state) ? state : kakaoAppRedirectUri;
        return destination + (destination.contains("?") ? "&" : "?") + "code=" + encode(code);
    }
    private AuthDtos.AuthResponse response(User user) { return new AuthDtos.AuthResponse(jwt.issue(user), user.getId(), user.getEmail(), user.getNickname()); }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."); }
    private void requireKakaoConfig() { if (kakaoClientId.isBlank() || kakaoRedirectUri.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "카카오 로그인 설정이 필요합니다."); }
    private boolean isAllowedAppRedirect(String state) {
        return state != null && (state.startsWith("finsight://auth/kakao") || state.startsWith("exp://"));
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
