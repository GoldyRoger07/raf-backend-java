package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Correction du numero de telephone, mot de passe a l'appui. */
public record ChangePhoneRequest(

        @NotBlank(message = "Le mot de passe est obligatoire.")
        String password,

        @NotBlank(message = "Le numero de telephone est obligatoire.")
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Le numero doit etre au format international, ex. +50912345678.")
        String phone) {
}
