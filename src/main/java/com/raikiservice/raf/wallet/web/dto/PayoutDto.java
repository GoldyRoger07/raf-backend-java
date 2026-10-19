package com.raikiservice.raf.wallet.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Versement d'un retrait approuve.
 *
 * <p>MonCash / NatCash : {@code payoutReference} (reference de l'operateur) est
 * obligatoire. Especes ({@code MANUAL}) : {@code pickupCode}, le code recu par le
 * joueur present au guichet, est obligatoire ; la reference (numero de recu) est
 * facultative. Le controle est fait par le service, la regle dependant de
 * l'operateur.
 */
public record PayoutDto(

        @Size(max = 120)
        String payoutReference,

        @Pattern(regexp = "^[0-9]{4,8}$", message = "Le code est invalide.")
        String pickupCode) {
}
