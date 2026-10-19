package com.raikiservice.raf.quiz.domain;

/**
 * Etats d'un quiz.
 *
 * <p>Le vote du sujet n'apparait pas ici : c'est une phase superposee a
 * {@link #REGISTRATION}, puisqu'il est reserve aux detenteurs de ticket et que
 * ceux-ci doivent donc pouvoir acheter avant que le sujet soit connu.
 */
public enum QuizStatus {
    /** En preparation, invisible des joueurs. */
    DRAFT,
    /** Tickets en vente ; vote du sujet en cours si le mode le prevoit. */
    REGISTRATION,
    /** Seuil de joueurs atteint : demarrage programme. */
    SCHEDULED,
    /** Les joueurs peuvent lancer leur partie. */
    RUNNING,
    /** Un joueur a depasse le score minimum : compte a rebours de cloture lance. */
    CLOSING,
    /** Cloture atteinte, classement en cours de calcul. */
    COMPUTING,
    /** Classement fige, recompenses versees. */
    FINISHED,
    /** Annule : tous les tickets ont ete rembourses. */
    CANCELLED
}
