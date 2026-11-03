package com.raikiservice.raf.admin.web.dto;

import java.time.Instant;

/** Fiche joueur du back-office : identite, verifications et soldes. */
public record PlayerSummary(
        Long id,
        String email,
        String phone,
        String firstName,
        String lastName,
        String status,
        boolean emailVerified,
        boolean phoneVerified,
        /** Compte de test : son activite est exclue du tableau de bord. */
        boolean test,
        long creditBalance,
        long winningsBalanceHtg,
        Instant createdAt) {
}
