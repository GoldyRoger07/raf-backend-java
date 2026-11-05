package com.raikiservice.raf.leader.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            /** Credits offerts a chaque joueur inscrit avec son lien, des qu'il confirme son email. */
            int signupBonusCredits,
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
            /** Inscrit avec le lien d'invitation du leader (et non ajoute par un administrateur). */
            boolean viaInvite,
            /** Bonus promis a l'inscription, 0 si aucun. */
            int signupBonusCredits,
            /** Versement du bonus, null tant que l'email n'est pas confirme. */
            Instant bonusPaidAt,
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

    public record SignupBonusRequest(
            @NotNull @Min(0) @Max(MAX_SIGNUP_BONUS_CREDITS) Integer signupBonusCredits) {
    }

    /** Plafond du bonus d'inscription, pour eviter une faute de frappe couteuse. */
    public static final int MAX_SIGNUP_BONUS_CREDITS = 100_000;

    /**
     * Lien d'invitation d'un joueur. {@code leader} : ses filleuls lui sont rattaches
     * et recoivent {@code signupBonusCredits} credits des qu'ils confirment leur email.
     */
    public record InviteResponse(String url, boolean leader, int signupBonusCredits) {
    }
}
