package com.raikiservice.raf.game.domain;

public enum GameSessionStatus {
    /** Questions envoyees, chrono en cours. */
    IN_PROGRESS,
    /** Reponses recues dans les temps et score recalcule par le serveur. */
    SUBMITTED,
    /** Aucune soumission avant l'echeance : la partie ne compte pas. */
    EXPIRED,
    /** Soumission incoherente ou annulation par un administrateur : score nul. */
    INVALIDATED
}
