package com.raikiservice.raf.admin.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Activite jour par jour (jours d'Haiti), hors quiz et comptes de test.
 *
 * <p>{@code today} court de minuit a maintenant ; {@code yesterday} est la journee
 * d'hier entiere et {@code yesterdaySameTime} la meme journee arretee a l'heure
 * actuelle : c'est la comparaison juste pour une journee en cours.
 * {@code days} va du plus ancien jour a aujourd'hui inclus.
 */
public record DailyStatsResponse(
        Instant generatedAt,
        long htgRate,
        Day today,
        Day yesterday,
        Day yesterdaySameTime,
        List<Day> days) {

    /**
     * Chiffres d'une journee. Montants en HTG, credits convertis au taux du moment.
     * {@code resultHtg} suit la formule du resultat du jeu (voir GameProfitService).
     */
    public record Day(
            LocalDate date,
            long newPlayers,
            long activePlayers,
            long ticketsSold,
            long soloGames,
            long salesHtg,
            long prizesHtg,
            long resultHtg,
            long topUpsHtg,
            long withdrawalsPaidHtg) {
    }
}
