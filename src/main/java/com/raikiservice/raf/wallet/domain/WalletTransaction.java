package com.raikiservice.raf.wallet.domain;

import com.raikiservice.raf.common.domain.BaseEntity;

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
 * Mouvement de portefeuille. Ligne immuable : jamais modifiee ni supprimee.
 *
 * <p>Une correction se fait par un mouvement inverse ({@code ADJUSTMENT}), jamais
 * en retouchant l'historique.
 */
@Getter
@Setter
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WalletTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false, length = 10)
    private BalanceType balanceType;

    /** Signe : positif pour un credit, negatif pour un debit. */
    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id", length = 60)
    private String referenceId;

    /**
     * Garde-fou contre le double debit : un second appel portant la meme cle est
     * rejete par la contrainte d'unicite plutot que rejoue.
     */
    @Column(name = "idempotency_key", length = 120, unique = true)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;
}
