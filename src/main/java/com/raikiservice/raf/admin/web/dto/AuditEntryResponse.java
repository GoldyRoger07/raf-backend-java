package com.raikiservice.raf.admin.web.dto;

import java.time.Instant;

/** Une ligne du journal d'audit, avec le nom de son auteur (null : systeme). */
public record AuditEntryResponse(
        Long id,
        Instant createdAt,
        Long actorUserId,
        String actorName,
        String action,
        String entityType,
        String entityId,
        String beforeState,
        String afterState,
        String ipAddress) {
}
