package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectionDto(

        @NotBlank(message = "Le motif est obligatoire.")
        @Size(max = 255)
        String reason) {
}
