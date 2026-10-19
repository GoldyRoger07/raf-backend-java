package com.raikiservice.raf.notification.service;

import java.util.Map;

import com.raikiservice.raf.notification.domain.NotificationType;

/**
 * Contenu d'une notification.
 *
 * @param data donnees de navigation pour le frontend (identifiant du quiz, du retrait...)
 * @param path chemin dans le frontend vers l'ecran concerne (ex. {@code /quiz/12}) :
 *             lien de l'email et cible du clic sur le push
 */
public record NotificationMessage(
        NotificationType type,
        String title,
        String body,
        Map<String, Object> data,
        String path) {

    public NotificationMessage {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** Destinataire et contenu, pour les envois personnalises (un rang, un montant). */
    public record Delivery(Long userId, NotificationMessage message) {
    }
}
