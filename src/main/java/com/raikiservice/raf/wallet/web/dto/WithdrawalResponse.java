package com.raikiservice.raf.wallet.web.dto;

import java.time.Instant;

import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;

public record WithdrawalResponse(
        Long id,
        Long userId,
        long amountCredits,
        long amountHtg,
        long feeHtg,
        long netHtg,
        PaymentProvider provider,
        String destinationNumber,
        WithdrawalStatus status,
        String rejectionReason,
        String payoutReference,
        Instant createdAt,
        Instant reviewedAt,
        Instant paidAt) {

    public static WithdrawalResponse from(WithdrawalRequest request) {
        return new WithdrawalResponse(
                request.getId(),
                request.getUser().getId(),
                request.getAmountCredits(),
                request.getAmountHtg(),
                request.getFeeHtg(),
                request.getNetHtg(),
                request.getProvider(),
                request.getDestinationNumber(),
                request.getStatus(),
                request.getRejectionReason(),
                request.getPayoutReference(),
                request.getCreatedAt(),
                request.getReviewedAt(),
                request.getPaidAt());
    }
}
