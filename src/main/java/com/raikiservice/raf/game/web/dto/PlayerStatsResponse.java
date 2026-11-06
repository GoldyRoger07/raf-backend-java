package com.raikiservice.raf.game.web.dto;

/**
 * Statistiques personnelles du joueur (onglet Actualite de l'accueil). Uniquement
 * ses propres parties : rien sur les autres joueurs ni sur leurs gains.
 */
public record PlayerStatsResponse(
        /** Parties terminees (soumises), en groupe et en solo. */
        long gamesPlayed,
        long groupGames,
        long soloGames,
        long correctAnswers,
        long answeredQuestions,
        /** Bonnes reponses sur reponses donnees, en %, null avant la premiere reponse. */
        Integer accuracyPercent,
        /** Quiz en groupe ou il figure au classement final. */
        long quizzesRanked,
        /** Meilleure place a un classement final, null s'il n'a jamais ete classe. */
        Integer bestRank,
        /** Classements finis dans les 3 premiers. */
        long podiums,
        /** Parties solo sans faute. */
        long perfectSoloGames,
        /** Tout ce qu'il a gagne (classements et sans-faute), en HTG. */
        long totalWinningsHtg,
        /** Jours d'affilee avec au moins une partie, jusqu'a aujourd'hui ou hier (heure d'Haiti). */
        int currentStreakDays,
        int bestStreakDays,
        /** Sujet qu'il joue le plus, null s'il n'a pas encore joue. */
        String favoriteSubject) {
}
