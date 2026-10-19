package com.raikiservice.raf.game.web.dto;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.question.domain.QuestionType;

/**
 * Partie lancee (ou reprise) : le tirage complet, sans aucun indicateur de bonne
 * reponse. Le frontend joue ensuite la partie entierement en local.
 *
 * <p>{@code remainingMs} est calcule par le serveur : en cas de reprise apres un
 * rechargement de page, le chrono repart de la ou il en etait, pas de zero.
 */
public record GameStartResponse(
        Long sessionId,
        Long quizId,
        boolean resumed,
        int durationSeconds,
        Instant startedAt,
        Instant playEndsAt,
        long remainingMs,
        List<QuestionView> questions) {

    public record QuestionView(
            Long id,
            int position,
            String text,
            String imageUrl,
            QuestionType type,
            List<ChoiceView> choices) {
    }

    /** Volontairement sans champ "correct". */
    public record ChoiceView(Long id, String text, String imageUrl) {
    }
}
