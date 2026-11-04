package com.raikiservice.raf.notification.domain;

public enum AnnouncementStatus {
    /** En attente de sa date d'envoi (immediate ou programmee). Annulable. */
    SCHEDULED,
    /** Destinataires figes, envoi en cours par lots. */
    SENDING,
    SENT,
    CANCELLED
}
