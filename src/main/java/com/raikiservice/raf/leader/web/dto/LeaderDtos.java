package com.raikiservice.raf.leader.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** Echanges de l'ecran Leaders du back-office. */
public final class LeaderDtos {

    private LeaderDtos() {
    }

    /** Un leader, avec son reglage et ce qu'il a touche. */
    public record LeaderSummary(
            Long id,
            String firstName,
            String lastName,
            String email,
            String phone,
            String status,
            BigDecimal commissionPercent,
            long playerCount,
            long commissionsLast30DaysHtg,
            long commissionsTotalHtg) {
    }

    /** Un joueur rattache a un leader. */
    public record LeaderPlayer(
            Long id,
            String firstName,
            String lastName,
            String email,
            String phone,
            String status,
            boolean test,
            Instant addedAt,
            /** Commissions que ce joueur a rapportees a ce leader. */
            long commissionsHtg,
            /** Debut de sa derniere partie, null s'il n'a jamais joue. */
            Instant lastPlayedAt) {
    }

    /** Une commission versee, detaillee par joueur. */
    public record LeaderCommission(
            Long id,
            Long playerId,
            String playerFirstName,
            String playerLastName,
            /** QUIZ (quiz en groupe) ou SOLO. */
            String source,
            Long quizId,
            String quizTitle,
            Long sessionId,
            long baseCredits,
            long htgRate,
            BigDecimal commissionPercent,
            long amountHtg,
            Instant createdAt) {
    }

    /** Nommer un leader : un compte joueur existant, et sa commission. */
    public record PromoteRequest(@NotNull Long userId, @NotNull BigDecimal commissionPercent) {
    }

    public record CommissionRequest(@NotNull BigDecimal commissionPercent) {
    }

    public record AddPlayerRequest(@NotNull Long playerId) {
    }
}
