package com.raikiservice.raf.reconciliation.repository;

import java.util.List;

import com.raikiservice.raf.reconciliation.domain.ReconciliationFinding;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, Long> {

    List<ReconciliationFinding> findByRunIdOrderByIdAsc(Long runId);
}
