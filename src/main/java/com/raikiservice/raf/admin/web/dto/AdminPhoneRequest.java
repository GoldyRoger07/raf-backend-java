package com.raikiservice.raf.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Nouveau numero saisi par un agent depuis la fiche joueur. */
public record AdminPhoneRequest(

        @NotBlank(message = "Le numero de telephone est obligatoire.")
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Le numero doit etre au format international, ex. +50912345678.")
        String phone) {
}
