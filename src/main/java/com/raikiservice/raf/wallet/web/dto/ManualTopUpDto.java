package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Recharge saisie par un administrateur apres encaissement hors ligne. */
public record ManualTopUpDto(

        @NotNull(message = "Le joueur est obligatoire.")
        Long userId,

        @Min(value = 1, message = "Le montant doit etre positif.")
        long amountHtg,

        /** Numero de recu ou de transaction : rend la recharge rejouable sans doublon. */
        @Size(max = 120)
        String reference,

        @Size(max = 255)
        String note) {
}
