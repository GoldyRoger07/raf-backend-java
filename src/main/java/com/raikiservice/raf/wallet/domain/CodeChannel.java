package com.raikiservice.raf.wallet.domain;

/** Canal par lequel un code de retrait est parti chez le joueur. */
public enum CodeChannel {
    SMS,
    EMAIL;

    /** D'apres la destination enregistree avec le code : une adresse email contient un @. */
    public static CodeChannel ofDestination(String destination) {
        return destination != null && destination.contains("@") ? EMAIL : SMS;
    }
}
