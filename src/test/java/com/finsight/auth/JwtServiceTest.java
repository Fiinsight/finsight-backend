package com.finsight.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = java.util.Base64.getEncoder().encodeToString(
            "finsight-test-jwt-secret-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void acceptsIssuedTokenAndRejectsExpiredOrTamperedTokens() {
        JwtService jwt = new JwtService(SECRET, 60);
        User user = new User("person@example.com", "hash", "User", AuthProvider.LOCAL);
        String issued = jwt.issue(user);
        Instant old = Instant.now().minusSeconds(120);
        String expired = Jwts.builder().subject(user.getEmail())
                .issuedAt(Date.from(old)).expiration(Date.from(old.plusSeconds(30)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();

        assertTrue(jwt.isValid(issued));
        assertEquals("person@example.com", jwt.subject(issued));
        assertFalse(jwt.isValid(expired));
        assertFalse(jwt.isValid(issued.substring(0, issued.length() - 1) + "x"));
    }
}
