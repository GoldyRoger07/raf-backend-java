package com.raikiservice.raf.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.OtpCode;
import com.raikiservice.raf.auth.domain.OtpPurpose;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    Optional<OtpCode> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(Long userId, OtpPurpose purpose);

    @Modifying
    @Query("update OtpCode o set o.consumedAt = :now "
            + "where o.user.id = :userId and o.purpose = :purpose and o.consumedAt is null")
    int consumePending(@Param("userId") Long userId,
            @Param("purpose") OtpPurpose purpose,
            @Param("now") Instant now);

    @Modifying
    @Query("update OtpCode o set o.attempts = o.attempts + 1 where o.id = :id")
    int incrementAttempts(@Param("id") Long id);
}
