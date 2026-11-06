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
    SOLO_ENTRY_DEBIT("Partie solo sans debit d'entree correspondant"),
    SOLO_PRIZE_PAYMENT("Sans-faute en solo sans versement correspondant"),
    LEADER_TRANSFER_DEBIT("Recharge par un leader sans debit correspondant du leader"),
    LEADER_TRANSFER_CREDIT("Recharge par un leader sans credit correspondant du joueur"),
    TOPUP_RETENTION("Recharge dont credits et retenue ne correspondent pas au montant recu"),
    RETENTION_CHAIN_BREAK("Retenue de recharge : solde apres different du solde precedent plus le montant"),
    UNEXPECTED_MOVEMENT("Mouvement lie a une operation qui ne devait pas en produire"),
    ORPHAN_TRANSACTION("Mouvement d'argent sans operation metier qui le reference");

    private final String description;

    ReconciliationCheck(String description) {
        this.description = description;
    }
}
