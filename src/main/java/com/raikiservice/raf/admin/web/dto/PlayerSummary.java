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
        Instant createdAt,
        /** Accord du paiement des retraits par tranches (PayoutTermsService), null s'il n'a pas accepte. */
        Instant payoutTermsAcceptedAt,
        /** Cas de retrait particulier, avec la note saisie a l'ajout. */
        boolean payoutSpecialCase,
        String payoutSpecialCaseNote) {
}
