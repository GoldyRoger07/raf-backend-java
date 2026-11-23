package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Nouvelles regles d'un quiz solo ouvert : elles valent pour les parties lancees ensuite,
 * jamais pour les parties passees ou en cours.
 *
 * @param confirmUnprofitable vrai pour enregistrer malgre une estimation perdante
 */
public record SoloTermsDto(
        @NotNull @Min(value = 0, message = "Le prix d'une partie ne peut pas etre negatif.")
        Integer entryPriceCredits,
        @NotNull @Min(value = 1, message = "Le prix d'un sans-faute doit etre positif.")
        Long prizeHtg,
        @NotNull @Min(value = 1, message = "Il faut au moins une question par partie.")
        Integer questionsPerGame,
        Boolean confirmUnprofitable) {
}
