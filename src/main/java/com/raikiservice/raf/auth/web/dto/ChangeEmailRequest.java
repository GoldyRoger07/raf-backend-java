package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Correction de l'adresse email.
 *
 * @param password mot de passe du compte : redemande par l'app, ignore cote back-office
 */
public record ChangeEmailRequest(

        String password,

        @NotBlank(message = "L'email est obligatoire.")
        @Email(message = "L'adresse email est invalide.")
        @Size(max = 180, message = "L'adresse email est trop longue.")
        String email) {
}
