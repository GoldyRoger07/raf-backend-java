package com.raikiservice.raf.game.web.dto;

import java.time.Instant;

import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.quiz.domain.GameMode;

/**
 * Resultat d'une partie tel que le voit le joueur : le score, jamais le detail
 * des reponses, qui n'est revele qu'a la cloture du quiz.
 *
 * <p>SOLO : {@code won} indique un sans-faute, et {@code prizeCredits} le prix
 * alors verse. Le joueur sait s'il a gagne, pas quelle reponse etait fausse.
 */
public record GameResultResponse(
        Long sessionId,
        Long quizId,
        GameSessionStatus status,
        GameMode gameMode,
        Instant startedAt,
        Instant submittedAt,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        Integer bestScore,
        boolean won,
        Long prizeCredits) {

    public static GameResultResponse from(GameSession session, Integer bestScore) {
        return new GameResultResponse(
                session.getId(),
                session.getQuiz().getId(),
                session.getStatus(),
                session.getGameMode(),
                session.getStartedAt(),
                session.getSubmittedAt(),
                session.getScore(),
                session.getCorrectCount(),
                session.getAnsweredCount(),
                bestScore,
                session.isWon(),
                session.isWon() ? session.getPrizeCredits() : null);
    }
}
