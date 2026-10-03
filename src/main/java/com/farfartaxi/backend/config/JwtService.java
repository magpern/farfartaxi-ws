package com.farfartaxi.backend.config;

import com.farfartaxi.backend.model.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    static final String PUBLIC_DEFAULT_SECRET = "replace-this-in-production-with-a-long-secret-value";
    private static final java.util.Set<String> REJECTED_SECRETS = java.util.Set.of(
        PUBLIC_DEFAULT_SECRET,
        "change-this-jwt-secret-to-a-long-random-value"
    );
    private static final int MIN_SECRET_LENGTH = 32;

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiration-minutes}") long expirationMinutes
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret (APP_JWT_SECRET) must be set");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("app.jwt.secret (APP_JWT_SECRET) must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        String normalized = secret.trim().toLowerCase(java.util.Locale.ROOT);
        String squashed = normalized.replace("-", "").replace("_", "");
        if (REJECTED_SECRETS.contains(normalized) || squashed.startsWith("changeme")) {
            throw new IllegalStateException("app.jwt.secret (APP_JWT_SECRET) must not be the public default value or a placeholder");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String generateToken(UserEntity user) {
        Instant now = Instant.now();
        var builder = Jwts.builder();
        if (user.getCredentialsChangedAt() != null) {
            builder.claim("cv", user.getCredentialsChangedAt().toEpochMilli());
        }
        return builder
            .subject(user.getEmail())
            .claim("uid", user.getId())
            .claim("role", user.getRole().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(expirationMinutes * 60)))
            .signWith(key)
            .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
