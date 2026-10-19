package com.raikiservice.raf.auth.domain;

public enum OtpPurpose {
    PHONE_VERIFY,
    WITHDRAWAL,
    /**
     * Autorisation d'un retrait au guichet. Distinct de WITHDRAWAL : un code
     * demande par le joueur pour un retrait en ligne ne doit pas pouvoir servir a
     * un agent pour un retrait en especes.
     */
    COUNTER_WITHDRAWAL,
    /**
     * Remise en especes d'un retrait demande par le joueur, au moment ou il se
     * presente au guichet. Distinct de COUNTER_WITHDRAWAL : le code d'une remise ne
     * doit pas pouvoir confirmer un retrait saisi par l'agent pour un autre montant.
     */
    CASH_PICKUP
}
