package com.raikiservice.raf.quiz.domain;

public enum TicketStatus {
    /** Achete, partie pas encore jouee. */
    ACTIVE,
    /** La partie correspondante a ete lancee. */
    USED,
    /** Quiz annule : les credits ont ete rendus. */
    REFUNDED
}
