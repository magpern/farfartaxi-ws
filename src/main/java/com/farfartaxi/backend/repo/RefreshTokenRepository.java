package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** Conditional rotation: exactly one concurrent caller gets 1. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenEntity t set t.replacedAt = :now, t.lastUsedAt = :now "
        + "where t.id = :id and t.replacedAt is null and t.revokedAt is null")
    int markRotated(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenEntity t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenEntity t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshTokenEntity t where t.expiresAt < :cutoff or t.revokedAt < :cutoff or t.replacedAt < :cutoff")
    int deleteStale(@Param("cutoff") Instant cutoff);
}
