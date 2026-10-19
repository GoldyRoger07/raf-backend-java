package com.raikiservice.raf.notification.domain;

public enum NotificationChannel {
    /** Toujours enregistree : c'est l'historique du joueur. Ne se desactive pas. */
    IN_APP,
    EMAIL,
    /** Push navigateur (Web Push, VAPID). */
    PUSH
}
