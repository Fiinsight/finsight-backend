package com.finsight.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration KAKAO_CALL_TIMEOUT = Duration.ofSeconds(8);
    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String kakaoClientId;
    private final String kakaoClientSecret;
    private final String kakaoRedirectUri;
    private final String kakaoAppRedirectUri;
    private final Set<String> kakaoWebRedirectUris;
    private final String kakaoTokenUri;
    private final String kakaoProfileUri;

    public AuthService(UserRepository users, JwtService jwt, WebClient.Builder webClientBuilder,
                       @Value("${finsight.auth.kakao-client-id:}") String kakaoClientId,
                       @Value("${finsight.auth.kakao-client-secret:}") String kakaoClientSecret,
                       @Value("${finsight.auth.kakao-redirect-uri:}") String kakaoRedirectUri,
                       @Value("${finsight.auth.kakao-app-redirect-uri:finsight://auth/kakao}") String kakaoAppRedirectUri,
                       @Value("${finsight.auth.kakao-web-redirect-uris:http://localhost:8081/auth/kakao,http://localhost:8082/auth/kakao,http://127.0.0.1:8081/auth/kakao,http://127.0.0.1:8082/auth/kakao}") String kakaoWebRedirectUris,
                       @Value("${finsight.auth.kakao-token-uri:https://kauth.kakao.com/oauth/token}") String kakaoTokenUri,
                       @Value("${finsight.auth.kakao-profile-uri:https://kapi.kakao.com/v2/user/me}") String kakaoProfileUri) {
        this.users = users; this.jwt = jwt; this.webClient = webClientBuilder.build();
        this.kakaoClientId = kakaoClientId; this.kakaoClientSecret = kakaoClientSecret; this.kakaoRedirectUri = kakaoRedirectUri; this.kakaoAppRedirectUri = kakaoAppRedirectUri;
        this.kakaoWebRedirectUris = Arrays.stream(kakaoWebRedirectUris.split(","))
                .map(String::trim)
                .filter(uri -> !uri.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.kakaoTokenUri = kakaoTokenUri;
        this.kakaoProfileUri = kakaoProfileUri;
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
    public AuthDtos.AuthResponse me(Long userId) {
        return users.findById(userId).map(this::response).orElseThrow(() -> unauthorized());
    }
    public AuthDtos.AuthResponse kakao(String code) {
        requireKakaoConfig();
        long startedAt = System.nanoTime();
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code"); form.add("client_id", kakaoClientId);
        form.add("redirect_uri", kakaoRedirectUri); form.add("code", code);
        if (!kakaoClientSecret.isBlank()) form.add("client_secret", kakaoClientSecret);
        JsonNode token;
        try {
            token = webClient.post().uri(kakaoTokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form).retrieve().bodyToMono(JsonNode.class).timeout(KAKAO_CALL_TIMEOUT).block();
        } catch (WebClientResponseException e) {
            log.warn("Kakao token exchange failed: status={}, elapsedMs={}", e.getStatusCode().value(), elapsedMs(startedAt));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 토큰 발급에 실패했습니다.");
        } catch (RuntimeException e) {
            log.warn("Kakao token exchange timed out or failed: elapsedMs={}", elapsedMs(startedAt));
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "카카오 인증 서버 응답이 지연되고 있습니다.");
        }
        if (token == null || token.get("access_token") == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 토큰 발급에 실패했습니다.");
        JsonNode profile;
        try {
            profile = webClient.get().uri(kakaoProfileUri).headers(h -> h.setBearerAuth(token.get("access_token").asText()))
                    .retrieve().bodyToMono(JsonNode.class).timeout(KAKAO_CALL_TIMEOUT).block();
        } catch (WebClientResponseException e) {
            log.warn("Kakao profile request failed: status={}, elapsedMs={}", e.getStatusCode().value(), elapsedMs(startedAt));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보를 가져오지 못했습니다.");
        } catch (RuntimeException e) {
            log.warn("Kakao profile request timed out or failed: elapsedMs={}", elapsedMs(startedAt));
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "카카오 사용자 정보 응답이 지연되고 있습니다.");
        }
        if (profile == null || profile.get("id") == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보를 가져오지 못했습니다.");
        String kakaoId = profile.get("id").asText();
        JsonNode account = profile.path("kakao_account");
        String kakaoFallbackEmail = "kakao-" + kakaoId + "@kakao.local";
        String email = account.path("email").asText("");
        String nickname = account.path("profile").path("nickname").asText("");
        if (nickname.isBlank()) nickname = profile.path("properties").path("nickname").asText("");
        if (email.isBlank() || nickname.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "카카오 로그인 동의항목에서 닉네임과 카카오계정 이메일을 허용해 주세요.");
        }
        final String resolvedEmail = email;
        final String resolvedNickname = nickname;
        User user = users.findByProviderAndProviderId(AuthProvider.KAKAO, kakaoId).orElseGet(() -> {
            // The Kakao member id is the stable identity. Email consent is
            // optional and a granted Kakao email may collide with a local one.
            String uniqueEmail = users.findByEmail(resolvedEmail)
                    .filter(existing -> existing.getProvider() != AuthProvider.KAKAO)
                    .isPresent() ? kakaoFallbackEmail : resolvedEmail;
            return users.save(new User(uniqueEmail, null, resolvedNickname, AuthProvider.KAKAO, kakaoId));
        });
        user.setNickname(resolvedNickname);
        if (!resolvedEmail.equals(kakaoFallbackEmail) && !users.findByEmail(resolvedEmail)
                .filter(existing -> existing != user && (existing.getId() == null || user.getId() == null
                        || !existing.getId().equals(user.getId()))).isPresent()) {
            user.setEmail(resolvedEmail);
        }
        users.save(user);
        log.info("Kakao login completed: providerId={}, elapsedMs={}", kakaoId, elapsedMs(startedAt));
        return response(user);
    }
    public String kakaoUrl(String state) {
        requireKakaoConfig();
        String url = "https://kauth.kakao.com/oauth/authorize?client_id=" + encode(kakaoClientId)
                + "&redirect_uri=" + encode(kakaoRedirectUri) + "&response_type=code"
                + "&scope=profile_nickname,account_email";
        if (state != null && !state.isBlank()) url += "&state=" + encode(state);
        return url;
    }
    public String kakaoAppRedirectUri() { return kakaoAppRedirectUri; }
    public AuthDtos.KakaoConfigStatus kakaoConfigStatus() {
        return new AuthDtos.KakaoConfigStatus(!kakaoClientId.isBlank() && !kakaoRedirectUri.isBlank(), kakaoRedirectUri, kakaoAppRedirectUri);
    }
    public String kakaoCallbackUri(String code, String state, String error, String errorDescription) {
        String destination = isAllowedAppRedirect(state) ? state : kakaoAppRedirectUri;
        String separator = destination.contains("?") ? "&" : "?";
        if (error != null && !error.isBlank()) {
            String result = destination + separator + "error=" + encode(error);
            if (errorDescription != null && !errorDescription.isBlank()) {
                result += "&error_description=" + encode(errorDescription);
            }
            return result;
        }
        return destination + separator + "code=" + encode(code);
    }
    private AuthDtos.AuthResponse response(User user) { return new AuthDtos.AuthResponse(jwt.issue(user), user.getId(), user.getEmail(), user.getNickname()); }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."); }
    private void requireKakaoConfig() { if (kakaoClientId.isBlank() || kakaoRedirectUri.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "카카오 로그인 설정이 필요합니다."); }
    private boolean isAllowedAppRedirect(String state) {
        return state != null && (state.startsWith("finsight://auth/kakao")
                || state.startsWith("exp://")
                || kakaoWebRedirectUris.contains(state));
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
}
