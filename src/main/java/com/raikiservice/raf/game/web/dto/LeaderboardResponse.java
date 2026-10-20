package com.raikiservice.raf.game.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Classement final d'un quiz, pagine.
 *
 * <p>{@code me} donne la ligne du joueur connecte meme quand elle n'est pas dans
 * la page demandee : c'est la premiere chose qu'il cherche.
 */
public record LeaderboardResponse(
        Long quizId,
        String quizTitle,
        Instant finishedAt,
        long totalRanked,
        int page,
        int size,
        List<Entry> entries,
        Entry me) {

    /** Le nom est abrege ("Jean D.") : le classement est public pour tous les joueurs. */
    public record Entry(int rank, String displayName, int score, long rewardHtg, boolean me) {
    }
}
