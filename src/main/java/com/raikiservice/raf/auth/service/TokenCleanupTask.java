package com.raikiservice.raf.auth.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.raikiservice.raf.auth.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purge des refresh tokens expires.
 *
 * <p>Une retention de 30 jours apres expiration est conservee : la table sert aussi
 * a enqueter sur une reutilisation de jeton signalee apres coup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupTask {

    private static final int RETENTION_DAYS = 30;

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);

        if (deleted > 0) {
            log.info("Purge des refresh tokens : {} ligne(s) supprimee(s)", deleted);
        }
    }
}
