package com.raikiservice.raf.wallet.domain;

public enum WithdrawalStatus {
    /** Demande creee, gains deja geles, en attente de revue. */
    PENDING,
    /** Validee par un administrateur, versement a effectuer. */
    APPROVED,
    /** Versement effectue chez l'operateur. */
    PAID,
    /** Refusee : les gains geles ont ete rendus au joueur. */
    REJECTED
}
