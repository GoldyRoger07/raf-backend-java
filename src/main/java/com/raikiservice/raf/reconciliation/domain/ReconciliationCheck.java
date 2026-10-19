package com.raikiservice.raf.reconciliation.domain;

import lombok.Getter;

/** Les controles du rapprochement. Le code est stable et destine au back-office. */
@Getter
public enum ReconciliationCheck {

    LEDGER_MISMATCH("Solde different de la somme de ses transactions"),
    BALANCE_CHAIN_BREAK("Solde apres mouvement different du solde precedent plus le montant"),
    TICKET_DEBIT("Ticket sans debit correspondant (type, montant ou proprietaire)"),
    TICKET_REFUND("Ticket rembourse sans remboursement correspondant"),
    TOPUP_CREDIT("Recharge confirmee sans credit correspondant"),
    WITHDRAWAL_DEBIT("Retrait sans debit correspondant des gains"),
    WITHDRAWAL_REFUND("Retrait rejete sans restitution des gains"),
    PRIZE_PAYMENT("Gain du classement sans versement correspondant"),
    UNEXPECTED_MOVEMENT("Mouvement lie a une operation qui ne devait pas en produire"),
    ORPHAN_TRANSACTION("Mouvement d'argent sans operation metier qui le reference");

    private final String description;

    ReconciliationCheck(String description) {
        this.description = description;
    }
}
