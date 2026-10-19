package com.raikiservice.raf.wallet.service;

import java.util.UUID;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correction manuelle de solde par un administrateur.
 *
 * <p>Sert aux cas que le flux normal ne couvre pas : geste commercial, reparation
 * d'une erreur, versement exceptionnel. Toujours motivee et tracee nominativement,
 * parce que c'est le seul endroit du systeme ou un humain cree de la valeur sans
 * contrepartie.
 */
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    private final WalletService walletService;
    private final AuditService auditService;

    @Transactional
    public WalletTransaction adjust(Long adminId, Long userId, BalanceType balanceType,
            long signedAmountCredits, String reason) {

        if (signedAmountCredits == 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "Le montant ne peut pas etre nul.");
        }

        // Cle unique par ajustement : deux corrections identiques restent possibles,
        // mais un double envoi de la meme requete ne l'est pas.
        String idempotencyKey = "adjustment:" + UUID.randomUUID();
        String description = "Ajustement administrateur : " + reason;

        WalletTransaction transaction = signedAmountCredits > 0
                ? walletService.credit(userId, balanceType, signedAmountCredits,
                        WalletTransactionType.ADJUSTMENT, "Admin", String.valueOf(adminId),
                        idempotencyKey, description)
                : walletService.debit(userId, balanceType, -signedAmountCredits,
                        WalletTransactionType.ADJUSTMENT, "Admin", String.valueOf(adminId),
                        idempotencyKey, description);

        auditService.record(adminId, "WALLET_ADJUSTED", "User", String.valueOf(userId),
                null, signedAmountCredits + " credits sur " + balanceType + " : " + reason);

        return transaction;
    }
}
