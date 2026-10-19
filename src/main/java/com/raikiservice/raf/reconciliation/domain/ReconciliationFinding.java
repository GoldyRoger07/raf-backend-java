package com.raikiservice.raf.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Un ecart releve. {@code expectedValue} est ce que le journal ou l'operation
 * metier impose, {@code actualValue} ce qui est reellement enregistre.
 */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_findings")
public class ReconciliationFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_code", nullable = false, length = 40)
    private ReconciliationCheck check;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "wallet_id")
    private Long walletId;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "expected_value")
    private Long expectedValue;

    @Column(name = "actual_value")
    private Long actualValue;

    @Column(length = 500)
    private String detail;
}
