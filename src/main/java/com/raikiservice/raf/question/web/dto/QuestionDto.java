package com.raikiservice.raf.question.web.dto;

import java.util.List;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuestionDto(

        @NotNull(message = "La categorie est obligatoire.")
        Long categoryId,

        @NotBlank(message = "L'enonce est obligatoire.")
        @Size(max = 2000)
        String text,

        QuestionType type,

        Difficulty difficulty,

        @Size(max = 1000)
        String explanation,

        Long mediaId,

        @Valid
        @NotEmpty(message = "Les propositions de reponse sont obligatoires.")
        @Size(min = 2, max = 6, message = "Entre 2 et 6 propositions.")
        List<ChoiceDto> choices) {
}
