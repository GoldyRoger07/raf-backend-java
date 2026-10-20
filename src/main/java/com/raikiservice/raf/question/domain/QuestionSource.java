package com.raikiservice.raf.question.domain;

/** Origine d'une question : la relecture et le suivi de qualite en dependent. */
public enum QuestionSource {
    /** Saisie dans le back-office (et questions anterieures a V13). */
    MANUAL,
    /** Import CSV. */
    IMPORT,
    /** Generee par un modele d'IA, toujours creee en brouillon. */
    AI
}
