package com.raikiservice.raf.admin.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Tableau de bord du back-office. Montants en credits, avec leur equivalent en
 * gourdes au taux courant.
 */
public record DashboardResponse(
        Instant generatedAt,
        long htgRate,
        Players players,
        Map<String, Long> quizzesByStatus,
        Money balances,
        Activity last30Days,
        Pending pending,
        LastReconciliation lastReconciliation) {

    public record Players(long total, long newLast7Days, long phoneVerified) {
    }

    /** Ce qui est en circulation : les gains sont ce que la plateforme doit aux joueurs. */
    /** Gains : directement en HTG. */
    public record Money(long creditBalance, long creditBalanceHtg, long winningsBalanceHtg) {
    }

    public record Activity(long ticketSalesCredits, long ticketSalesHtg, long ticketsSold,
            long prizesPaidHtg, long topUpsCredits, long topUpsHtg,
            long withdrawalsPaidHtg) {
    }

    /** Le travail qui attend un administrateur. */
    public record Pending(long withdrawalsToReview, long withdrawalsToReviewHtg,
            long withdrawalsToPay, long flaggedSessions, long topUpsPending) {
    }

    public record LastReconciliation(Long id, String status, Instant startedAt, int findingCount) {
    }
}
