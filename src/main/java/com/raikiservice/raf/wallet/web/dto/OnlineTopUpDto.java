package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.PaymentProvider;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Recharge en ligne : montant credite (hors frais) et moyen de paiement (MONCASH ou NATCASH). */
public record OnlineTopUpDto(
        @Min(value = 1, message = "Le montant doit etre positif.")
        long amountHtg,

        @NotNull
        PaymentProvider provider) {
}
