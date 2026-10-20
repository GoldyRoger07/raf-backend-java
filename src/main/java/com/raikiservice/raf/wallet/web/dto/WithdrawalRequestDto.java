package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.PaymentProvider;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Demande de retrait par le joueur.
 *
 * <p>{@code destinationNumber} est obligatoire pour MonCash / NatCash, et ignore
 * pour {@code MANUAL} (especes remises au guichet) : le controle est fait par le
 * service, la regle dependant de l'operateur.
 */
public record WithdrawalRequestDto(

        /** Montant retire des gains, en HTG, frais compris. */
        @Min(value = 1, message = "Le montant doit etre positif.")
        long amountHtg,

        @NotNull(message = "L'operateur est obligatoire.")
        PaymentProvider provider,

        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Le numero doit etre au format international, ex. +50912345678.")
        String destinationNumber,

        @NotBlank(message = "Le code de confirmation est obligatoire.")
        @Pattern(regexp = "^[0-9]{4,8}$", message = "Le code est invalide.")
        String otpCode) {
}
