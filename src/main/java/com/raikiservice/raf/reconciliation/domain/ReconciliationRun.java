package com.raikiservice.raf.reconciliation.domain;

import java.time.Instant;

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

/** Une execution du rapprochement, avec le bilan des soldes a cet instant. */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private ReconciliationTrigger trigger;

    @Column(name = "triggered_by")
    private Long triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "wallets_checked", nullable = false)
    private long walletsChecked;

    @Column(name = "transactions_checked", nullable = false)
    private long transactionsChecked;

    /** Nombre total d'ecarts, y compris ceux au-dela du detail conserve. */
    @Column(name = "finding_count", nullable = false)
    private int findingCount;

    /** Credits en circulation (non retirables). */
    @Column(name = "total_credit_balance", nullable = false)
    private long totalCreditBalance;

    /** Gains retirables : c'est ce que la plateforme doit aux joueurs. */
    @Column(name = "total_winnings_balance", nullable = false)
    private long totalWinningsBalance;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
