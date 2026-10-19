package com.raikiservice.raf.common.audit;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId,
            Pageable pageable);

    /** Consultation du journal : chaque filtre est facultatif (null = pas de filtre). */
    @Query("select a from AuditLog a where (:action is null or a.action = :action) "
            + "and (:entityType is null or a.entityType = :entityType) "
            + "and (:entityId is null or a.entityId = :entityId) "
            + "and (:actorId is null or a.actorUserId = :actorId) "
            + "order by a.createdAt desc, a.id desc")
    Page<AuditLog> search(@Param("action") String action, @Param("entityType") String entityType,
            @Param("entityId") String entityId, @Param("actorId") Long actorId, Pageable pageable);

    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findDistinctActions();

    @Query("select distinct a.entityType from AuditLog a where a.entityType is not null order by a.entityType")
    List<String> findDistinctEntityTypes();
}
