package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Tranche d'un retrait MonCash / NatCash versee a la main : montant net et reference de l'operateur. */
public record InstallmentDto(
        @Positive long amountHtg,
        @NotBlank @Size(max = 120) String payoutReference) {
}
