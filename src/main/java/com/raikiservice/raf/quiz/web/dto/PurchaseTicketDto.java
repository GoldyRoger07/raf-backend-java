package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Achat d'un ticket.
 *
 * <p>La cle d'idempotence est fournie par le frontend (un UUID genere au moment
 * ou l'ecran d'achat s'ouvre). Elle protege du double clic : deux envois portant
 * la meme cle renvoient le meme ticket.
 */
public record PurchaseTicketDto(

        @Size(max = 120)
        String idempotencyKey) {
}
