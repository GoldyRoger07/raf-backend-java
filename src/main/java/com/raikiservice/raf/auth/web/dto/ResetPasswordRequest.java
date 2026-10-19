package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @NotBlank(message = "Le jeton est obligatoire.")
        String token,

        @NotBlank(message = "Le nouveau mot de passe est obligatoire.")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir au moins 8 caracteres.")
        String newPassword) {
}
