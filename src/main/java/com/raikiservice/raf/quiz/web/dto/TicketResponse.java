package com.raikiservice.raf.quiz.web.dto;

import java.time.Instant;

import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;

public record TicketResponse(
        Long id,
        Long quizId,
        String quizTitle,
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
                ticket.getCode(),
                ticket.getPricePaidCredits(),
                ticket.getStatus(),
                ticket.getPurchasedAt(),
                ticket.getUsedAt());
    }
}
