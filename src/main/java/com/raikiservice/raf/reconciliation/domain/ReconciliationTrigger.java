package com.raikiservice.raf.reconciliation.domain;

public enum ReconciliationTrigger {
    /** Execution planifiee (chaque nuit par defaut). */
    SCHEDULED,
    /** Lancee a la demande par un administrateur. */
    MANUAL
}
