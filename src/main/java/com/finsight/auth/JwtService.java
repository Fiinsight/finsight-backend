package com.finsight.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import org.springframework.util.StringUtils;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final String PUBLIC_DEFAULT_BASE64 = "Zm9yLWxvY2FsLWRldmVsb3BtZW50LXNlY3JldC1jaGFuZ2UtbWU=";
    private static final String PUBLIC_DEFAULT_PLAIN = "for-local-development-secret-change-me";
    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(@Value("${finsight.auth.jwt-secret}") String secret,
                      @Value("${finsight.auth.expiration-seconds:86400}") long expirationSeconds) {
        if (!StringUtils.hasText(secret)
                || PUBLIC_DEFAULT_BASE64.equals(secret.trim())
                || PUBLIC_DEFAULT_PLAIN.equals(secret.trim())) {
            throw new IllegalStateException("JWT_SECRET must be configured with a private base64-encoded key");
        }
        try {
            this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("JWT_SECRET must be valid base64 and long enough for JWT signing", exception);
        }
        this.expirationSeconds = expirationSeconds;
    }
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.getEmail()).claim("uid", user.getId())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key).compact();
    }
    public String subject(String token) { return claims(token).getSubject(); }
    public Long userId(String token) { return claims(token).get("uid", Long.class); }
    public boolean isValid(String token) {
        try { return claims(token).getExpiration().after(new Date()); }
        catch (RuntimeException e) { return false; }
    }
    private Claims claims(String token) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload(); }
}
