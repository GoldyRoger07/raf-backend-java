package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PayoutDto(

        @NotBlank(message = "La reference du versement est obligatoire.")
        @Size(max = 120)
        String payoutReference) {
}
