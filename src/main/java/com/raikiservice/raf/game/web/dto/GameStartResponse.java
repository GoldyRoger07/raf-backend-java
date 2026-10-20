package com.raikiservice.raf.game.web.dto;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.quiz.domain.PlayMode;

/**
 * Partie lancee (ou reprise), sans aucun indicateur de bonne reponse.
 *
 * <ul>
 *   <li>BATCH : {@code questions} porte tout le tirage, {@code current} est null.
 *       Le frontend joue la partie en local, question par question avec un chrono
 *       de {@code questionDurationSeconds}, puis soumet tout d'un bloc.
 *   <li>SEQUENTIAL : {@code questions} est vide, {@code current} porte la seule
 *       question en cours. Les suivantes arrivent une a une, en reponse a
 *       {@code POST /api/game/{sessionId}/answer}.
 * </ul>
 *
 * <p>Les temps restants sont calcules par le serveur : en cas de reprise apres
 * un rechargement de page, le chrono repart de la ou il en etait, pas de zero.
 */
public record GameStartResponse(
        Long sessionId,
        Long quizId,
        boolean resumed,
        PlayMode playMode,
        int questionDurationSeconds,
        int totalQuestions,
        int durationSeconds,
        Instant startedAt,
        Instant playEndsAt,
        long remainingMs,
        List<QuestionView> questions,
        CurrentQuestion current) {

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

    /**
     * SEQUENTIAL : la question a jouer. {@code remainingMs} peut valoir 0 a la
     * reprise d'une question deja expiree : le client envoie alors une reponse
     * vide pour passer a la suivante.
     */
    public record CurrentQuestion(QuestionView question, Instant endsAt, long remainingMs) {
    }
}
