package com.raikiservice.raf.wallet.web.dto;

import java.time.Instant;

import com.raikiservice.raf.wallet.domain.WithdrawalPayout;

/** Tranche deja versee d'un retrait. */
public record InstallmentResponse(Long id, long amountHtg, String payoutReference, Long adminId,
        Instant createdAt) {

    public static InstallmentResponse from(WithdrawalPayout payout) {
        return new InstallmentResponse(payout.getId(), payout.getAmountHtg(), payout.getPayoutReference(),
                payout.getAdminId(), payout.getCreatedAt());
    }
}
