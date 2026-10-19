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

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
