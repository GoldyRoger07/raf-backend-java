package com.raikiservice.raf.auth.domain;

public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    BANNED,
    /** Supprime par son titulaire : donnees personnelles effacees, historique garde. */
    DELETED
}
