package com.raikiservice.raf.game.web.dto;

import java.util.List;

/**
 * Classement d'un quiz solo (preuve sociale) : resume du quiz, les meilleurs joueurs
 * et la place de celui qui regarde.
 */
public record SoloLeaderboardResponse(
        Long quizId,
        String quizTitle,
        /** Nombre de questions d'une partie : le score s'affiche « 9/10 ». */
        int questionsPerGame,
        long players,
        long perfects,
        long wonHtg,
        List<Entry> entries,
        /** La place du joueur qui regarde ; null s'il n'a pas encore fini de partie. */
        Entry me) {

    /** Un joueur : nom masque, meilleur score (bonnes reponses), gains et sans-fautes sur ce quiz. */
    public record Entry(int rank, String displayName, int bestCorrect, long wonHtg, int perfects, boolean me) {
    }
}
