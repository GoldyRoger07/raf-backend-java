package com.raikiservice.raf.wallet.domain;

public enum WalletTransactionType {
    /** Recharge confirmee : credite le solde CREDIT. */
    TOPUP,
    /** Achat d'un ticket de quiz : debite le solde CREDIT. */
    TICKET_PURCHASE,
    /** Lancement d'une partie solo : debite le solde CREDIT. */
    SOLO_ENTRY,
    /** Gain d'un quiz (rang au classement, ou sans-faute en solo) : credite le solde WINNINGS. */
    PRIZE,
    /** Demande de retrait : debite (gele) le solde WINNINGS. */
    WITHDRAWAL,
    /** Remboursement : quiz annule, ou retrait rejete. */
    REFUND,
    /** Correction manuelle par un administrateur. Toujours tracee. */
    ADJUSTMENT
}
