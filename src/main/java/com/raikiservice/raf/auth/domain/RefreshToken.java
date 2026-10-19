package com.raikiservice.raf.auth.domain;

import java.time.Instant;

import com.raikiservice.raf.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Jeton de rafraichissement, stocke hache.
 *
 * <p>Chaque connexion ouvre une "famille" ({@code familyId}). Un rafraichissement
 * revoque le jeton courant et en emet un nouveau dans la meme famille. Si un jeton
 * deja revoque est rejoue, c'est qu'il a fuite : toute la famille est alors revoquee.
 */
@Getter
@Setter
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 40)
    private String revokedReason;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "device_label", length = 120)
    private String deviceLabel;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }

    public void revoke(String reason) {
        this.revokedAt = Instant.now();
        this.revokedReason = reason;
    }
}
