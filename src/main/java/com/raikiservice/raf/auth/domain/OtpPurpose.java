package com.raikiservice.raf.auth.domain;

public enum OtpPurpose {
    PHONE_VERIFY,
    WITHDRAWAL,
    /**
     * Autorisation d'un retrait au guichet. Distinct de WITHDRAWAL : un code
     * demande par le joueur pour un retrait en ligne ne doit pas pouvoir servir a
     * un agent pour un retrait en especes.
     */
    COUNTER_WITHDRAWAL
}
