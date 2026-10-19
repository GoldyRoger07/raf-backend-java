package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(

        @NotBlank(message = "Le code est obligatoire.")
        @Pattern(regexp = "^[0-9]{4,8}$", message = "Le code est invalide.")
        String code) {
}
