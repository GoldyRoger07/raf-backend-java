package com.raikiservice.raf.reconciliation.domain;

public enum ReconciliationStatus {
    /** Aucun ecart. */
    OK,
    /** Au moins un ecart : a examiner. */
    DISCREPANCIES,
    /** Le controle n'a pas pu aller au bout : il ne prouve rien. */
    FAILED
}
