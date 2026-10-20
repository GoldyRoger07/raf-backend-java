package com.raikiservice.raf.game.web.dto;

import java.util.List;

import com.raikiservice.raf.game.domain.GameSessionStatus;

/**
 * Corrige d'une partie tel que le voit le joueur : pour chaque question, juste,
 * fausse ou sans reponse. Ni enonce, ni propositions, ni bonne reponse : les
 * questions restent dans le vivier et ne doivent pas circuler avec leurs
 * reponses. Le corrige detaille est reserve au back-office.
 */
public record GameOutcomeResponse(
        Long sessionId,
        Long quizId,
        GameSessionStatus status,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        List<Outcome> items) {

    public enum Result {
        CORRECT,
        WRONG,
        /** Chrono expire, ou question jamais servie (partie abandonnee). */
        UNANSWERED
    }

    public record Outcome(int position, Result result) {
    }
}
