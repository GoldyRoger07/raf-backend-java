package com.raikiservice.raf.leader.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Echanges de l'ecran Recharger un joueur de l'app joueur, reserve aux leaders. */
public final class LeaderTransferDtos {

    private LeaderTransferDtos() {
    }

    /** Ce que l'ecran doit savoir avant d'afficher le formulaire. */
    public record TransferConfig(
            boolean enabled,
            long minCredits,
            long maxCredits,
            /** Solde de credits du leader, plafond reel d'une recharge. */
            long creditBalance) {
    }

    /** Un joueur rattache au leader, tel qu'affiche dans la liste. */
    public record TransferPlayer(
            Long id,
            String firstName,
            String lastName,
            /** Quatre derniers chiffres du telephone, pour distinguer deux homonymes. */
            String phoneEnd,
            /** Derniere recharge de ce leader a ce joueur, null s'il n'y en a jamais eu. */
            Instant lastTransferAt,
            Long lastTransferCredits) {
    }

    public record TransferRequest(
            @NotNull Long playerId,
            @NotNull @Min(1) Long credits,
            /** Genere par l'app pour chaque recharge : un double envoi n'est applique qu'une fois. */
            @NotBlank @Size(max = 64) String requestKey) {
    }

    /** Une recharge effectuee. */
    public record TransferResponse(
            Long id,
            Long playerId,
            String playerFirstName,
            String playerLastName,
            long credits,
            Instant createdAt,
            /** Solde de credits du leader apres la recharge (null dans l'historique). */
            Long leaderBalanceAfter) {
    }
}
