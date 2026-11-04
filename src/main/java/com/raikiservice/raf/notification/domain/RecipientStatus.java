package com.raikiservice.raf.notification.domain;

public enum RecipientStatus {
    PENDING,
    DONE,
    /** Compte supprime entre le gel des destinataires et l'envoi. */
    SKIPPED,
    FAILED
}
