package com.raikiservice.raf.notification.web.dto;

/** Resultat d'un essai : ce qui est effectivement parti vers le compte de l'auteur. */
public record AnnouncementTestResponse(boolean popup, boolean emailSent, boolean pushSent,
        String email) {
}
