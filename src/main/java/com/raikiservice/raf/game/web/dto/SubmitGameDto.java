package com.raikiservice.raf.game.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Reponses d'une partie, envoyees en une fois a la fin du chrono.
 *
 * <p>Les reponses sont attendues dans l'ordre du tirage : le jeu interdit de
 * sauter une question, la reponse n correspond donc forcement a la question n.
 * Le client n'envoie aucun score : il serait ignore.
 */
public record SubmitGameDto(

        @NotNull(message = "La partie est obligatoire.")
        Long sessionId,

        @NotNull
        @Size(max = 500)
        List<@Valid AnswerDto> answers,

        @PositiveOrZero
        Long clientDurationMs) {

    public record AnswerDto(

            @NotNull
            Long questionId,

            @NotNull
            Long choiceId,

            /** Temps ecoule depuis le debut de la partie, mesure par le client. */
            @NotNull
            @PositiveOrZero
            Long elapsedMs) {
    }
}
