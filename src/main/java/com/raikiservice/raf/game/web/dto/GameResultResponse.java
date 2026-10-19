package com.raikiservice.raf.game.web.dto;

import java.time.Instant;

import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionStatus;

/**
 * Resultat d'une partie tel que le voit le joueur : le score, jamais le detail
 * des reponses, qui n'est revele qu'a la cloture du quiz.
 */
public record GameResultResponse(
        Long sessionId,
        Long quizId,
        GameSessionStatus status,
        Instant startedAt,
        Instant submittedAt,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        Integer bestScore) {

    public static GameResultResponse from(GameSession session, Integer bestScore) {
        return new GameResultResponse(
                session.getId(),
                session.getQuiz().getId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getSubmittedAt(),
                session.getScore(),
                session.getCorrectCount(),
                session.getAnsweredCount(),
                bestScore);
    }
}
