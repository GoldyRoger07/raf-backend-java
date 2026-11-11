package com.raikiservice.raf.wallet.web.dto;

import java.time.Instant;

import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;

public record WithdrawalResponse(
        Long id,
        Long userId,
        long amountHtg,
        long feeHtg,
        long netHtg,
        /** Deja verse : somme des tranches, ou le net une fois paye en une fois. */
        long paidHtg,
        /** Reste non verse rendu aux gains a la cloture d'un retrait paye en partie. */
        long refundedHtg,
        PaymentProvider provider,
        String destinationNumber,
        WithdrawalStatus status,
        String rejectionReason,
        String payoutReference,
        Instant createdAt,
        Instant reviewedAt,
        Instant paidAt,
        /** Agent ayant saisi un retrait au guichet ; null pour un retrait en ligne. */
        Long initiatedByAdminId,
        String note,
        /** Frais de la passerelle au pourcentage : envoi automatique a l'approbation. */
        boolean automaticPayout,
        /** Raison du dernier echec d'envoi automatique, a traiter par l'admin. */
        String payoutError,
        int payoutAttempts) {

    public static WithdrawalResponse from(WithdrawalRequest request) {
        return new WithdrawalResponse(
                request.getId(),
                request.getUser().getId(),
                request.getAmountHtg(),
                request.getFeeHtg(),
                request.getNetHtg(),
                request.getPaidHtg(),
                request.getRefundedHtg(),
                request.getProvider(),
                request.getDestinationNumber(),
                request.getStatus(),
                request.getRejectionReason(),
                request.getPayoutReference(),
                request.getCreatedAt(),
                request.getReviewedAt(),
                request.getPaidAt(),
                request.getInitiatedByAdminId(),
                request.getNote(),
                request.isAutomaticPayout(),
                request.getPayoutError(),
                request.getPayoutAttempts());
    }
}
