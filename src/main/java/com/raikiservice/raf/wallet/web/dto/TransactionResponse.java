package com.raikiservice.raf.wallet.web.dto;

import java.time.Instant;

import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;

public record TransactionResponse(
        Long id,
        WalletTransactionType type,
        BalanceType balanceType,
        long amount,
        long balanceAfter,
        String description,
        String referenceType,
        String referenceId,
        Instant createdAt) {

    public static TransactionResponse from(WalletTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getBalanceType(),
                transaction.getAmount(),
                transaction.getBalanceAfter(),
                transaction.getDescription(),
                transaction.getReferenceType(),
                transaction.getReferenceId(),
                transaction.getCreatedAt());
    }
}
