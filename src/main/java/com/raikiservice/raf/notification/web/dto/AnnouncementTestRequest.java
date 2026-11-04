package com.raikiservice.raf.notification.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Essai d'un message sur son propre compte, avant l'envoi reel. */
public record AnnouncementTestRequest(
        boolean popup,
        boolean push,
        boolean email,
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 1000) String body,
        @Size(max = 255)
        @Pattern(regexp = "^/(?![/\\\\])\\S*$",
                message = "Le lien doit etre un chemin de l'app commencant par / (ex. /quiz/12).")
        String linkPath) {
}
