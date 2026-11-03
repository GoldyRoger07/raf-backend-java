package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Confirmation de la suppression du compte.
 *
 * @param password       mot de passe actuel, redemande avant une operation sans retour
 * @param forfeitBalance le joueur renonce a ce qui reste sur son portefeuille
 *                       (obligatoire si un solde n'est pas nul)
 */
public record DeleteAccountRequest(

        @NotBlank(message = "Le mot de passe est obligatoire.")
        String password,

        boolean forfeitBalance) {
}
