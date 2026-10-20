package com.raikiservice.raf.question.web.dto;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Demande de generation par IA. {@code difficulty} et {@code type} vides =
 * melange ; le plafond par demande est un parametre applicatif.
 */
public record GenerateQuestionsDto(

        @NotNull(message = "La categorie est obligatoire.")
        Long categoryId,

        @Min(value = 1, message = "Au moins une question.")
        @Max(value = 100, message = "100 questions au plus.")
        int count,

        Difficulty difficulty,

        QuestionType type,

        @Size(max = 500, message = "Consignes : 500 caracteres au plus.")
        String instructions) {
}
