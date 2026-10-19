package com.raikiservice.raf.game.web.dto;

import java.time.Instant;

import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionStatus;

/** Vue back-office d'une partie, avec les signaux d'abus. */
public record AdminGameSessionResponse(
        Long id,
        Long quizId,
        Long userId,
        String userEmail,
        Long ticketId,
        GameSessionStatus status,
        Instant startedAt,
        Instant serverDeadlineAt,
        Instant submittedAt,
        Long clientDurationMs,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        boolean flagged,
        String flagReason,
        String invalidationReason,
        String ip,
        String userAgent) {

    public static AdminGameSessionResponse from(GameSession session) {
        return new AdminGameSessionResponse(
                session.getId(),
                session.getQuiz().getId(),
                session.getUser().getId(),
                session.getUser().getEmail(),
                session.getTicket().getId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getServerDeadlineAt(),
                session.getSubmittedAt(),
                session.getClientDurationMs(),
                session.getScore(),
                session.getCorrectCount(),
                session.getAnsweredCount(),
                session.isFlagged(),
                session.getFlagReason(),
                session.getInvalidationReason(),
                session.getIp(),
                session.getUserAgent());
    }
}
