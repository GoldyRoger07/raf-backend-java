package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Recharge avec les gains, demandee par le joueur.
 *
 * <p>{@code amountHtg} est le montant voulu ; seule la part qui forme des credits
 * entiers est debitee. {@code requestKey} est tire au sort par l'app pour chaque
 * conversion : un nouvel essai apres une coupure reseau n'est pas applique deux fois.
 */
public record WinningsConversionDto(
        @Min(value = 1, message = "Le montant doit etre positif.")
        long amountHtg,

        @NotBlank @Size(max = 64)
        String requestKey) {
}
