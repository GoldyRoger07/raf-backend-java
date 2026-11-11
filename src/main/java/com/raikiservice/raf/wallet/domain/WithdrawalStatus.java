package com.raikiservice.raf.wallet.domain;

public enum WithdrawalStatus {
    /** Retrait au guichet saisi par un admin, en attente du code du joueur. Rien n'est debite. */
    AWAITING_OTP,
    /** Demande creee, gains deja geles, en attente de revue. */
    PENDING,
    /** Validee par un administrateur, versement a effectuer. */
    APPROVED,
    /**
     * Envoi automatique en cours chez la passerelle (Tranzak) : les gains restent
     * geles ; le retrait passe en PAID, ou revient en APPROVED si l'envoi echoue.
     */
    SENDING,
    /**
     * Une partie du montant net a ete versee a la main (withdrawal_payouts) ; le reste
     * suit par d'autres tranches, ou est rendu aux gains si l'admin clot le retrait.
     */
    PARTIALLY_PAID,
    /**
     * Versement effectue chez l'operateur, ou especes remises au guichet. Apres des
     * tranches, le retrait peut etre clos avec un reste rendu ({@code refundedHtg}).
     */
    PAID,
    /** Refusee : les gains geles ont ete rendus au joueur. */
    REJECTED,
    /** Retrait au guichet abandonne avant confirmation. Rien n'avait ete debite. */
    CANCELLED
}
