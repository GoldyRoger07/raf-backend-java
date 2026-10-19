package com.raikiservice.raf.game.web.dto;

import jakarta.validation.constraints.NotNull;

/** Lancement d'une partie : le serveur choisit lui-meme le ticket a consommer. */
public record StartGameDto(

        @NotNull(message = "Le quiz est obligatoire.")
        Long quizId) {
}
