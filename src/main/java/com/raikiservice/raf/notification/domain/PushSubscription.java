package com.raikiservice.raf.notification.domain;

import java.time.Instant;

import com.raikiservice.raf.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Abonnement push d'un navigateur, tel que fourni par l'API Push du navigateur. */
@Getter
@Setter
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    @Column(name = "endpoint_hash", nullable = false, length = 64)
    private String endpointHash;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(name = "auth_key", nullable = false, length = 100)
    private String authKey;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
