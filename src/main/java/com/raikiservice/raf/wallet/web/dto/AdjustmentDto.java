package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.BalanceType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Correction manuelle de solde. Le montant est signe : negatif pour retirer. */
public record AdjustmentDto(

        @NotNull(message = "Le solde vise est obligatoire.")
        BalanceType balanceType,

        long amountCredits,

        @NotBlank(message = "Le motif est obligatoire.")
        @Size(max = 255)
        String reason) {
}
