package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(

        @NotBlank(message = "L'email est obligatoire.")
        @Email(message = "L'adresse email est invalide.")
        String email) {
}
