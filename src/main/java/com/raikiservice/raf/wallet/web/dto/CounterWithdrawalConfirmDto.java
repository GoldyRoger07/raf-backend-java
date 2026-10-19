package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Confirmation d'un retrait au guichet avec le code recu par le joueur. */
public record CounterWithdrawalConfirmDto(

        @NotBlank(message = "Le code du joueur est obligatoire.")
        @Pattern(regexp = "^[0-9]{4,8}$", message = "Le code est invalide.")
        String otpCode,

        /** Numero du recu remis au joueur, si le guichet en emet. */
        @Size(max = 120)
        String receiptReference) {
}
