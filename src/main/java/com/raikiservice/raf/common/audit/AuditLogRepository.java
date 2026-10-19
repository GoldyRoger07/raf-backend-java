package com.raikiservice.raf.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId,
            Pageable pageable);
}
