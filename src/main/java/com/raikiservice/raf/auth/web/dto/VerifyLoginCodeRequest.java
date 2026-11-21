package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Seconde etape de la connexion du personnel : le challenge et le code recu par email. */
public record VerifyLoginCodeRequest(

        @NotBlank(message = "La connexion a expire, recommencez.")
        String mfaToken,

        @NotBlank(message = "Le code est obligatoire.")
        @Pattern(regexp = "^[0-9]{4,8}$", message = "Le code est invalide.")
        String code) {
}
