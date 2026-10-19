package com.raikiservice.raf.wallet.domain;

public enum WalletTransactionType {
    /** Recharge confirmee : credite le solde CREDIT. */
    TOPUP,
    /** Achat d'un ticket de quiz : debite le solde CREDIT. */
    TICKET_PURCHASE,
    /** Gain d'un quiz : credite le solde WINNINGS. */
    PRIZE,
    /** Demande de retrait : debite (gele) le solde WINNINGS. */
    WITHDRAWAL,
    /** Remboursement : quiz annule, ou retrait rejete. */
    REFUND,
    /** Correction manuelle par un administrateur. Toujours tracee. */
    ADJUSTMENT
}
