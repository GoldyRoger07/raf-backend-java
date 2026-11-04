package com.raikiservice.raf.notification.web.dto;

/**
 * Portee d'un envoi, affichee avant de confirmer.
 *
 * @param withEmail destinataires dont l'email est verifie (seuls a recevoir l'email)
 * @param withPush destinataires abonnes au push sur au moins un appareil
 */
public record AudienceResponse(long total, long withEmail, long withPush) {
}
