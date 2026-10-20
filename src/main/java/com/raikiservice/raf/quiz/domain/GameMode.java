package com.raikiservice.raf.quiz.domain;

/** Les deux facons de jouer un quiz. */
public enum GameMode {
    /**
     * Quiz collectif : tickets, seuil de joueurs, classement a la cloture et
     * recompenses par rang.
     */
    GROUP,
    /**
     * Quiz individuel ouvert en continu : chaque partie est payee en credits au
     * lancement, et un sans-faute rapporte le prix fixe par l'admin. Un joueur ne
     * peut jouer qu'une partie par sujet et par jour.
     */
    SOLO
}
