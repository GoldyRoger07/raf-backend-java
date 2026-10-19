package com.raikiservice.raf.auth.service;

import java.time.Instant;

import com.raikiservice.raf.auth.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoque une famille de refresh tokens dans une transaction independante.
 *
 * <p>La detection de reutilisation se termine par une exception, qui annule la
 * transaction courante. Sans cette transaction separee, la revocation partirait
 * avec elle : le jeton vole resterait valable et la detection ne protegerait rien.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(String familyId, String reason) {
        return refreshTokenRepository.revokeFamily(familyId, reason, Instant.now());
    }
}
