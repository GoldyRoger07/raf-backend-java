package com.raikiservice.raf.wallet.web.dto;

import java.time.Instant;

import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;

public record TopUpResponse(
        Long id,
        Long userId,
        PaymentProvider provider,
        long amountHtg,
        long credits,
        TopUpStatus status,
        String providerRef,
        String note,
        Instant createdAt,
        Instant confirmedAt,
        /** Valeur du credit a la confirmation, null tant que la recharge n'est pas confirmee. */
        Long htgRate,
        /** Retenue precedente ajoutee a cette recharge. */
        long retentionUsedHtg,
        /** Reste garde pour la recharge suivante. */
        long retentionKeptHtg) {

    public static TopUpResponse from(TopUpRequest request) {
        return new TopUpResponse(
                request.getId(),
                request.getUser().getId(),
                request.getProvider(),
                request.getAmountHtg(),
                request.getCredits(),
                request.getStatus(),
                request.getProviderRef(),
                request.getNote(),
                request.getCreatedAt(),
                request.getConfirmedAt(),
                request.getHtgRate(),
                request.getRetentionUsedHtg(),
                request.getRetentionKeptHtg());
    }
}
