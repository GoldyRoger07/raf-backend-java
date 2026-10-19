package com.raikiservice.raf.wallet.domain;

public enum PaymentProvider {
    MONCASH,
    NATCASH,
    /** Recharge saisie par un administrateur apres paiement hors ligne. */
    MANUAL
}
