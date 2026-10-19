package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Verification d'email : le jeton arrive du lien recu par email. */
public record TokenOnlyRequest(

        @NotBlank(message = "Le jeton est obligatoire.")
        String token) {
}
