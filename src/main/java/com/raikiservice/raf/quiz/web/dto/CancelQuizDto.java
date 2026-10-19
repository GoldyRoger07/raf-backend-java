package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelQuizDto(

        @NotBlank(message = "Le motif est obligatoire.")
        @Size(max = 255)
        String reason) {
}
