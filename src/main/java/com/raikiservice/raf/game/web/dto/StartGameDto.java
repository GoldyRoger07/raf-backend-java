package com.raikiservice.raf.game.web.dto;

import jakarta.validation.constraints.NotNull;

/** Lancement d'une partie : le serveur choisit lui-meme le ticket a consommer. */
public record StartGameDto(

        @NotNull(message = "Le quiz est obligatoire.")
        Long quizId,
        /** Solo : prix affiche au joueur (credits). Facultatif ; s'il a change, la partie est refusee. */
        Integer expectedPriceCredits,
        /** Solo : prime affichee au joueur (HTG). Facultatif. */
        Long expectedPrizeHtg,
        /** Solo : nombre de questions affiche au joueur. Facultatif. */
        Integer expectedQuestions) {
}
