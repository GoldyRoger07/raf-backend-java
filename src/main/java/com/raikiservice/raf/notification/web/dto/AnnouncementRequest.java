package com.raikiservice.raf.notification.web.dto;

import java.time.Instant;

import com.raikiservice.raf.notification.domain.AnnouncementAudience;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Message a envoyer depuis le back-office.
 *
 * @param targetUserId destinataire, obligatoire pour {@code ONE_PLAYER}
 * @param important message de service : passe outre les preferences email / push
 * @param linkPath chemin dans l'app (ex. /quiz/12) ; jamais une adresse externe
 * @param scheduledAt date d'envoi ; vide = tout de suite
 * @param popupExpiresAt fin d'affichage du popup ; vide = 7 jours apres l'envoi
 */
public record AnnouncementRequest(
        @NotNull AnnouncementAudience audience,
        Long targetUserId,
        boolean includeTest,
        boolean important,
        boolean popup,
        boolean push,
        boolean email,
        @NotBlank @Size(max = 120, message = "Titre : 120 caracteres au plus.") String title,
        @NotBlank @Size(max = 1000, message = "Message : 1000 caracteres au plus.") String body,
        @Size(max = 255)
        @Pattern(regexp = "^/(?![/\\\\])\\S*$",
                message = "Le lien doit etre un chemin de l'app commencant par / (ex. /quiz/12).")
        String linkPath,
        Instant scheduledAt,
        Instant popupExpiresAt) {
}
