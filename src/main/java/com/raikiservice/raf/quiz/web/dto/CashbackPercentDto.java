package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Cashback du quasi sans-faute sur un quiz solo : null = taux global, 0 = pas de cashback,
 * 1 a 100 = taux propre au quiz (% du prix d'entree rendu en credits).
 */
public record CashbackPercentDto(
        @Min(value = 0, message = "Le taux ne peut pas etre negatif.")
        @Max(value = 100, message = "Le taux ne peut pas depasser 100 %.")
        Integer percent) {
}
