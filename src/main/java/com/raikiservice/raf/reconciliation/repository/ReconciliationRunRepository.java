package com.raikiservice.raf.reconciliation.repository;

import java.time.Instant;

import com.raikiservice.raf.reconciliation.domain.ReconciliationRun;
import com.raikiservice.raf.reconciliation.domain.ReconciliationStatus;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    Page<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    /**
     * Historique filtre ; criteres facultatifs. {@code term} (deja entoure de {@code %})
     * cherche dans le message d'erreur, {@code id} est le numero d'execution quand la
     * recherche en est un. Ordre : celui du Pageable.
     */
    @Query("select r from ReconciliationRun r where (:status is null or r.status = :status) "
            + "and (:trigger is null or r.trigger = :trigger) "
            + "and (:from is null or r.startedAt >= :from) "
            + "and (:to is null or r.startedAt < :to) "
            + "and (:term is null or lower(r.errorMessage) like :term or r.id = :id)")
    Page<ReconciliationRun> search(@Param("status") ReconciliationStatus status,
            @Param("trigger") ReconciliationTrigger trigger, @Param("from") Instant from, @Param("to") Instant to,
            @Param("term") String term, @Param("id") Long id, Pageable pageable);
}
