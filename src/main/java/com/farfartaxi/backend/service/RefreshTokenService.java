package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RefreshTokenEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RefreshTokenRepository;
import com.farfartaxi.backend.repo.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rotating refresh tokens. Raw tokens are never stored or logged; only their SHA-256 hex hash. */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public sealed interface RefreshResult {
        record Success(UserEntity user, String newToken) implements RefreshResult { }
        record Invalid() implements RefreshResult { }
        record Race() implements RefreshResult { }
    }

    private final RefreshTokenRepository repo;
    private final UserRepository userRepository;
    private final Clock clock;
    private final long lifetimeDays;
    private final long graceSeconds;

    public RefreshTokenService(
        RefreshTokenRepository repo,
        UserRepository userRepository,
        Clock clock,
        @Value("${app.auth.refresh-days:90}") long lifetimeDays,
        @Value("${app.auth.refresh-grace-seconds:30}") long graceSeconds
    ) {
        this.repo = repo;
        this.userRepository = userRepository;
        this.clock = clock;
        this.lifetimeDays = lifetimeDays;
        this.graceSeconds = graceSeconds;
    }

    public Duration lifetime() {
        return Duration.ofDays(lifetimeDays);
    }

    /** Starts a new family and returns the raw token (to be set as cookie only). */
    @Transactional
    public String issueNewFamily(Long userId, String userAgent) {
        return create(userId, UUID.randomUUID().toString(), userAgent);
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        repo.revokeAllForUser(userId, Instant.now(clock));
    }

    /** Revokes the token presented (if known); no-op otherwise. */
    @Transactional
    public void revokeByRawToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        repo.findByTokenHash(hash(raw)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(Instant.now(clock));
                repo.save(t);
            }
        });
    }

    /** Never throws for expected failures so that a family revocation is always committed. */
    @Transactional
    public RefreshResult refresh(String raw, String userAgent) {
        if (raw == null || raw.isBlank()) {
            return new RefreshResult.Invalid();
        }
        Instant now = Instant.now(clock);
        RefreshTokenEntity t = repo.findByTokenHash(hash(raw)).orElse(null);
        if (t == null || t.getRevokedAt() != null || !t.getExpiresAt().isAfter(now)) {
            return new RefreshResult.Invalid();
        }
        if (t.getReplacedAt() != null) {
            return reuse(t, now);
        }
        UserEntity user = userRepository.findById(t.getUserId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return new RefreshResult.Invalid();
        }
        Long id = t.getId();
        String family = t.getFamilyId();
        if (repo.markRotated(id, now) != 1) {
            // Lost a concurrent race (or token revoked meanwhile): re-evaluate against the committed state.
            RefreshTokenEntity fresh = repo.findById(id).orElse(null);
            if (fresh == null || fresh.getRevokedAt() != null || fresh.getReplacedAt() == null) {
                return new RefreshResult.Invalid();
            }
            return reuse(fresh, now);
        }
        return new RefreshResult.Success(user, create(user.getId(), family, userAgent));
    }

    private RefreshResult reuse(RefreshTokenEntity t, Instant now) {
        if (t.getReplacedAt().isAfter(now.minusSeconds(graceSeconds))) {
            return new RefreshResult.Race();
        }
        repo.revokeFamily(t.getFamilyId(), now);
        log.warn("Refresh token reuse detected; family revoked userId={} familyId={}", t.getUserId(), t.getFamilyId());
        return new RefreshResult.Invalid();
    }

    private String create(Long userId, String familyId, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now(clock);
        RefreshTokenEntity t = new RefreshTokenEntity();
        t.setUserId(userId);
        t.setTokenHash(hash(raw));
        t.setFamilyId(familyId);
        t.setCreatedAt(now);
        t.setExpiresAt(now.plus(lifetime()));
        t.setUserAgent(userAgent == null ? null : (userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent));
        repo.save(t);
        return raw;
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deletes tokens expired/revoked/replaced more than 7 days ago. */
    @Transactional
    public int cleanup() {
        return repo.deleteStale(Instant.now(clock).minus(Duration.ofDays(7)));
    }
}
