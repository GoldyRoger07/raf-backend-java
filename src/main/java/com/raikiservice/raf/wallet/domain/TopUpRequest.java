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
 * Demande de recharge.
 *
 * <p>Le solde n'est alimente qu'au passage en {@link TopUpStatus#CONFIRMED} : une
 * demande PENDING ne vaut rien tant que l'argent n'est pas arrive. Le couple
 * (provider, provider_ref) est unique pour qu'un webhook rejoue deux fois ne
 * credite pas deux fois.
 */
@Getter
@Setter
@Entity
@Table(name = "topup_requests")
public class TopUpRequest extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "amount_htg", nullable = false)
    private long amountHtg;

    @Column(nullable = false)
    private long credits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TopUpStatus status = TopUpStatus.PENDING;

    /** Identifiant cote operateur, ou reference du recu pour une recharge manuelle. */
    @Column(name = "provider_ref", length = 120)
    private String providerRef;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(length = 255)
    private String note;

    @Column(name = "confirmed_by_admin_id")
    private Long confirmedByAdminId;

    @Column(name = "transaction_id")
    private Long transactionId;

    /** Conversion de gains (fournisseur WINNINGS) : debit du solde de gains qui la paie. */
    @Column(name = "debit_transaction_id")
    private Long debitTransactionId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** Valeur du credit en gourdes au moment de la confirmation. */
    @Column(name = "htg_rate")
    private Long htgRate;

    /** Retenue precedente ajoutee a cette recharge. */
    @Column(name = "retention_used_htg", nullable = false)
    private long retentionUsedHtg;

    /** Reste de cette recharge, garde pour la suivante. */
    @Column(name = "retention_kept_htg", nullable = false)
    private long retentionKeptHtg;

    /** Recharge en ligne : frais de la passerelle payes par le joueur, en plus du montant. */
    @Column(name = "fee_htg", nullable = false)
    private long feeHtg;

    /** Recharge en ligne : montant facture au joueur (montant + frais), null sinon. */
    @Column(name = "charged_htg")
    private Long chargedHtg;

    /** Recharge en ligne : page de paiement de la passerelle. */
    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    /** Recharge en ligne : derniere verification de l'etat aupres de la passerelle. */
    @Column(name = "checked_at")
    private Instant checkedAt;
}
