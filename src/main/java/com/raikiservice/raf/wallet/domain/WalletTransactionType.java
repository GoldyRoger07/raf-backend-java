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
    /** Commission d'un leader sur les parties perdues de ses joueurs : credite le solde WINNINGS. */
    COMMISSION,
    /**
     * Bonus offert par la plateforme a un joueur inscrit avec le lien d'un leader,
     * des qu'il confirme son email : credite le solde CREDIT.
     */
    INVITE_BONUS,
    /** Recharge d'un joueur par son leader : debite le solde CREDIT du leader. */
    LEADER_TRANSFER_OUT,
    /** Recharge d'un joueur par son leader : credite le solde CREDIT du joueur. */
    LEADER_TRANSFER_IN,
    /** Correction manuelle par un administrateur. Toujours tracee. */
    ADJUSTMENT
}
