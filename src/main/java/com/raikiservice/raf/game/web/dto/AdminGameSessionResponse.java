package com.raikiservice.raf.game.web.dto;

import java.time.Instant;

import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;

/** Vue back-office d'une partie, avec les signaux d'abus. */
public record AdminGameSessionResponse(
        Long id,
        Long quizId,
        String quizTitle,
        Long userId,
        String userEmail,
        String playerName,
        Long ticketId,
        GameSessionStatus status,
        GameMode gameMode,
        PlayMode playMode,
        Instant startedAt,
        Instant serverDeadlineAt,
        Instant submittedAt,
        Long clientDurationMs,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        boolean won,
        boolean flagged,
        String flagReason,
        String invalidationReason,
        String ip,
        String userAgent) {

    public static AdminGameSessionResponse from(GameSession session) {
        return new AdminGameSessionResponse(
                session.getId(),
                session.getQuiz().getId(),
                session.getQuiz().getTitle(),
                session.getUser().getId(),
                session.getUser().getEmail(),
                session.getUser().fullName(),
                session.getTicket() == null ? null : session.getTicket().getId(),
                session.getStatus(),
                session.getGameMode(),
                session.getPlayMode(),
                session.getStartedAt(),
                session.getServerDeadlineAt(),
                session.getSubmittedAt(),
                session.getClientDurationMs(),
                session.getScore(),
                session.getCorrectCount(),
                session.getAnsweredCount(),
                session.isWon(),
                session.isFlagged(),
                session.getFlagReason(),
                session.getInvalidationReason(),
                session.getIp(),
                session.getUserAgent());
    }
}
