package com.raikiservice.raf.auth.domain;

public enum RoleName {
    PLAYER,
    MODERATOR,
    ADMIN,
    SUPER_ADMIN,
    /**
     * Joueur a qui l'on confie d'autres joueurs : il touche une commission sur leurs
     * parties perdues. Ce n'est PAS un role du personnel : il joue, se supprime et
     * recoit les annonces comme tout joueur.
     */
    LEADER;

    /** Role du personnel (back-office) : moderateur, administrateur, super-administrateur. */
    public boolean isStaff() {
        return this == MODERATOR || this == ADMIN || this == SUPER_ADMIN;
    }
}
