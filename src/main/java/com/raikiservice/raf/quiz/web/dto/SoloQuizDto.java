package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Quiz solo : un sujet fixe, un prix par partie, un prix pour un sans-faute.
 * Toujours joue question par question (SEQUENTIAL), sans ticket ni classement.
 */
public record SoloQuizDto(

        @NotBlank(message = "Le titre est obligatoire.")
        @Size(max = 160)
        String title,

        @Size(max = 1000)
        String description,

        /** Doit etre un sujet propose en mode Solo. */
        @NotNull(message = "Le sujet est obligatoire.")
        Long categoryId,

        Long bannerMediaId,

        @Min(value = 1, message = "Le prix d'une partie doit etre positif.")
        int entryPriceCredits,

        @Min(value = 1, message = "Le prix d'un sans-faute doit etre positif.")
        long prizeCredits,

        @Min(value = 1, message = "Il faut au moins une question par partie.")
        int questionsPerGame,

        @Min(value = 3, message = "Une question dure au moins 3 secondes.")
        @Max(value = 300, message = "Une question dure au plus 5 minutes.")
        int questionDurationSeconds) {
}
