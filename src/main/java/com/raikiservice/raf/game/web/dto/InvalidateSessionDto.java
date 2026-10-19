package com.raikiservice.raf.game.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InvalidateSessionDto(

        @NotBlank(message = "Le motif est obligatoire.")
        @Size(max = 255)
        String reason) {
}
