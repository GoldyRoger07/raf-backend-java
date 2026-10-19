package com.raikiservice.raf.common.audit;

import com.raikiservice.raf.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Trace immuable d'une action sensible. Jamais modifiee apres insertion. */
@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id", length = 60)
    private String entityId;

    @Column(name = "before_state", columnDefinition = "TEXT")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "TEXT")
    private String afterState;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;
}
