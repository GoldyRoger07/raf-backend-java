package com.raikiservice.raf.notification.domain;

/** Destinataires d'une annonce. */
public enum AnnouncementAudience {
    /** Un compte precis, choisi dans le back-office. */
    ONE_PLAYER,
    /** Tous les joueurs actifs (hors equipe, hors comptes de test sauf demande). Super-admin. */
    ALL_PLAYERS
}
