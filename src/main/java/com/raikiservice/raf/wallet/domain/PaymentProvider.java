package com.raikiservice.raf.wallet.domain;

public enum PaymentProvider {
    MONCASH,
    NATCASH,
    /** Recharge saisie par un administrateur apres paiement hors ligne. */
    MANUAL,
    /**
     * Recharge payee par le joueur avec son solde de gains (HTG), convertis en credits.
     * Jamais un moyen de retrait : voir WithdrawalService.
     */
    WINNINGS
}
