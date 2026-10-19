package com.raikiservice.raf.auth.domain;

import java.time.Instant;

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
 * Code a usage unique envoye par SMS.
 *
 * <p>Un code a 6 chiffres n'offre que 10^6 combinaisons : la protection ne vient pas
 * de sa longueur mais du plafond d'essais et de la duree de vie courte.
 */
@Getter
@Setter
@Entity
@Table(name = "otp_codes")
public class OtpCode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OtpPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(length = 120)
    private String destination;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(nullable = false)
    private int attempts;

    public boolean isUsable() {
        return consumedAt == null && expiresAt.isAfter(Instant.now());
    }
}
