package com.raikiservice.raf.quiz.web.dto;

import java.time.Instant;

import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;

public record TicketResponse(
        Long id,
        Long quizId,
        String quizTitle,
        QuizStatus quizStatus,
        String code,
        int pricePaidCredits,
        TicketStatus status,
        Instant purchasedAt,
        Instant usedAt) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getQuiz().getId(),
                ticket.getQuiz().getTitle(),
                ticket.getQuiz().getStatus(),
                ticket.getCode(),
                ticket.getPricePaidCredits(),
                ticket.getStatus(),
                ticket.getPurchasedAt(),
                ticket.getUsedAt());
    }
}
