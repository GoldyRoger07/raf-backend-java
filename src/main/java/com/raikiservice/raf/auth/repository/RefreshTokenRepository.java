package com.raikiservice.raf.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.RefreshToken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("select t from RefreshToken t where t.user.id = :userId "
            + "and t.revokedAt is null and t.expiresAt > :now order by t.createdAt desc")
    List<RefreshToken> findActiveByUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Revoque toute une famille : utilise des qu'une reutilisation est detectee. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId,
            @Param("reason") String reason,
            @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason "
            + "where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId,
            @Param("reason") String reason,
            @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
