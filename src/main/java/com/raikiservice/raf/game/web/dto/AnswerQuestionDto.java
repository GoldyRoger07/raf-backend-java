package com.raikiservice.raf.game.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Reponse a la question en cours d'une partie SEQUENTIAL.
 *
 * <p>{@code questionId} rend l'envoi rejouable : un nouvel essai reseau d'une
 * reponse deja enregistree est reconnu et ne s'applique pas a la question
 * suivante. Aucun temps n'est envoye : c'est le serveur qui mesure.
 */
public record AnswerQuestionDto(

        @NotNull(message = "La question est obligatoire.")
        Long questionId,

        /** Null : le joueur passe la question, ou son chrono a expire. */
        Long choiceId) {
}
