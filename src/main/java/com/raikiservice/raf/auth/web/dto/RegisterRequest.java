package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "L'email est obligatoire.")
        @Email(message = "L'adresse email est invalide.")
        @Size(max = 180)
        String email,

        @NotBlank(message = "Le numero de telephone est obligatoire.")
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Le numero doit etre au format international, ex. +50912345678.")
        String phone,

        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir au moins 8 caracteres.")
        String password,

        @NotBlank(message = "Le prenom est obligatoire.")
        @Size(max = 80)
        String firstName,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 80)
        String lastName) {
}
