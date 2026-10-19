package com.raikiservice.raf.wallet.domain;

public enum WithdrawalStatus {
    /** Retrait au guichet saisi par un admin, en attente du code du joueur. Rien n'est debite. */
    AWAITING_OTP,
    /** Demande creee, gains deja geles, en attente de revue. */
    PENDING,
    /** Validee par un administrateur, versement a effectuer. */
    APPROVED,
    /** Versement effectue chez l'operateur, ou especes remises au guichet. */
    PAID,
    /** Refusee : les gains geles ont ete rendus au joueur. */
    REJECTED,
    /** Retrait au guichet abandonne avant confirmation. Rien n'avait ete debite. */
    CANCELLED
}
