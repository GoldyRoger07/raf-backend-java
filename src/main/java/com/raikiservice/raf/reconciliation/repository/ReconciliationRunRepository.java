package com.raikiservice.raf.reconciliation.repository;

import com.raikiservice.raf.reconciliation.domain.ReconciliationRun;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    Page<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
