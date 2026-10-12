package com.finsight.auth;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.mock;
class OAuthStateMvcTest {
 @Test void invalidAndReusedStatesRedirectWithoutCode() throws Exception {
  var service = new AuthService(mock(UserRepository.class), mock(JwtService.class), WebClient.builder(), "client", "", "http://localhost:8080/api/auth/kakao/callback", "finsight://auth/kakao", "http://localhost:8081/auth/kakao", "https://unused.test/token", "https://unused.test/profile");
  var mvc = MockMvcBuilders.standaloneSetup(new AuthController(service, mock(AuthRateLimiter.class))).build();
  mvc.perform(get("/api/auth/kakao/callback").param("state", "invalid").param("code", "code"))
    .andExpect(status().isFound()).andExpect(redirectedUrl("finsight://auth/kakao?error=invalid_state"));
  String nonce = AuthServiceTest.nonce(service, "http://localhost:8081/auth/kakao");
  mvc.perform(get("/api/auth/kakao/callback").param("state", nonce).param("code", "code"))
    .andExpect(redirectedUrl("http://localhost:8081/auth/kakao?code=code"));
  mvc.perform(get("/api/auth/kakao/callback").param("state", nonce).param("code", "code"))
    .andExpect(redirectedUrl("finsight://auth/kakao?error=invalid_state"));
 }
}
