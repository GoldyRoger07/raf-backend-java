package com.raikiservice.raf.auth.domain;

public enum VerificationTokenType {
    EMAIL_VERIFY,
    PASSWORD_RESET,
    /**
     * Challenge de connexion du personnel : prouve que le mot de passe vient d'etre
     * saisi. Seul, il ne donne aucun acces : il faut aussi le code recu par email.
     */
    STAFF_LOGIN
}
