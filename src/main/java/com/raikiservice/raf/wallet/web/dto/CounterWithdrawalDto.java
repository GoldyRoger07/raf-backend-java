package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Saisie d'un retrait au guichet. Le montant est en gourdes : c'est ce que l'agent remet. */
public record CounterWithdrawalDto(

        @Min(value = 1, message = "Le montant doit etre positif.")
        long amountHtg,

        @Size(max = 255)
        String note) {
}
