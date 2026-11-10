package com.raikiservice.raf.common.audit;

import java.time.Instant;

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

    /**
     * Consultation du journal : chaque filtre est facultatif (null = pas de filtre).
     * {@code term} (deja entoure de {@code %}) cherche dans l'action, l'objet, l'adresse IP,
     * le contenu du changement et le nom ou l'email de l'auteur. Ordre : celui du Pageable.
     */
    @Query("select a from AuditLog a where (:action is null or a.action = :action) "
            + "and (:entityType is null or a.entityType = :entityType) "
            + "and (:entityId is null or a.entityId = :entityId) "
            + "and (:actorId is null or a.actorUserId = :actorId) "
            + "and (:from is null or a.createdAt >= :from) "
            + "and (:to is null or a.createdAt < :to) "
            + "and (:term is null or lower(a.action) like :term or lower(a.entityType) like :term "
            + "  or lower(a.entityId) like :term or a.ipAddress like :term "
            + "  or lower(a.beforeState) like :term or lower(a.afterState) like :term "
            + "  or a.actorUserId in (select u.id from User u "
            + "    where lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''))) like :term "
            + "    or lower(u.email) like :term))")
    Page<AuditLog> search(@Param("action") String action, @Param("entityType") String entityType,
            @Param("entityId") String entityId, @Param("actorId") Long actorId,
            @Param("term") String term, @Param("from") Instant from, @Param("to") Instant to,
            Pageable pageable);

    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findDistinctActions();

    @Query("select distinct a.entityType from AuditLog a where a.entityType is not null order by a.entityType")
    List<String> findDistinctEntityTypes();
}
