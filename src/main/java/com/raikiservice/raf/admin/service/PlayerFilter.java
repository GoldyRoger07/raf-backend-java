package com.raikiservice.raf.admin.service;

import java.time.LocalDate;

import com.raikiservice.raf.auth.domain.UserStatus;

/**
 * Filtres de la liste des joueurs du back-office. Un champ {@code null} ne filtre pas ;
 * les filtres se combinent entre eux (ET) et avec la recherche texte.
 *
 * @param query          telephone, email ou nom (recherche partielle)
 * @param emailVerified  email confirme ou non
 * @param phoneVerified  telephone verifie ou non
 * @param hasCredit      solde de credits strictement positif, ou nul
 * @param hasWinnings    gains a retirer strictement positifs, ou nuls
 * @param status         statut du compte
 * @param test           comptes de test seulement, ou hors comptes de test
 * @param createdFrom    inscrit ce jour-la ou apres (jour d'Haiti)
 * @param createdTo      inscrit ce jour-la ou avant (jour d'Haiti)
 * @param hasToppedUp    au moins une recharge confirmee, ou aucune
 * @param activity       activite de jeu (parties commencees)
 */
public record PlayerFilter(
        String query,
        Boolean emailVerified,
        Boolean phoneVerified,
        Boolean hasCredit,
        Boolean hasWinnings,
        UserStatus status,
        Boolean test,
        LocalDate createdFrom,
        LocalDate createdTo,
        Boolean hasToppedUp,
        Activity activity) {

    public static PlayerFilter query(String query) {
        return new PlayerFilter(query, null, null, null, null, null, null, null, null, null, null);
    }

    /** Activite de jeu, d'apres les parties commencees (groupe ou solo). */
    public enum Activity {
        /** Au moins une partie ces 7 derniers jours. */
        LAST_7_DAYS,
        /** Au moins une partie ces 30 derniers jours. */
        LAST_30_DAYS,
        /** A deja joue, mais plus depuis 30 jours : les joueurs a relancer. */
        INACTIVE_30_DAYS,
        /** N'a jamais commence de partie. */
        NEVER
    }
}
