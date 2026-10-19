package com.raikiservice.raf.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.VerificationToken;
import com.raikiservice.raf.auth.domain.VerificationTokenType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    Optional<VerificationToken> findByTokenHashAndType(String tokenHash, VerificationTokenType type);

    /** Un nouveau jeton invalide les precedents du meme type. */
    @Modifying
    @Query("update VerificationToken t set t.consumedAt = :now "
            + "where t.user.id = :userId and t.type = :type and t.consumedAt is null")
    int consumePending(@Param("userId") Long userId,
            @Param("type") VerificationTokenType type,
            @Param("now") Instant now);
}
