package com.raikiservice.raf.wallet.domain;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Demande de retrait des gains.
 *
 * <p>Les gains sont debites des la creation de la demande, pas a la validation :
 * sinon trois demandes envoyees en parallele passeraient toutes les trois le
 * controle de solde. Un rejet rend les gourdes au joueur.
 */
@Getter
@Setter
@Entity
@Table(name = "withdrawal_requests")
public class WithdrawalRequest extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;


    @Column(name = "amount_htg", nullable = false)
    private long amountHtg;

    @Column(name = "fee_htg", nullable = false)
    private long feeHtg;

    /** Ce que le joueur recoit reellement : montant moins les frais. */
    @Column(name = "net_htg", nullable = false)
    private long netHtg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    /** Numero de reception chez l'operateur. Null pour un retrait au guichet (especes). */
    @Column(name = "destination_number", length = 20)
    private String destinationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    @Column(name = "otp_verified_at")
    private Instant otpVerifiedAt;

    /** Renseigne pour un retrait au guichet : l'agent qui l'a saisi. */
    @Column(name = "initiated_by_admin_id")
    private Long initiatedByAdminId;

    @Column(name = "debit_transaction_id")
    private Long debitTransactionId;

    @Column(name = "refund_transaction_id")
    private Long refundTransactionId;

    @Column(name = "reviewed_by_admin_id")
    private Long reviewedByAdminId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "payout_reference", length = 120)
    private String payoutReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(length = 255)
    private String note;

    public boolean isCounterWithdrawal() {
        return initiatedByAdminId != null;
    }
}
