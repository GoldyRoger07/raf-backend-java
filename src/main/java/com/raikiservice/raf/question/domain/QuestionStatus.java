package com.raikiservice.raf.question.domain;

public enum QuestionStatus {
    /** En cours de redaction : jamais tiree dans un quiz. */
    DRAFT,
    /** Validee : entre dans le pool des quiz. */
    PUBLISHED,
    /** Retiree du pool, conservee pour l'historique des parties jouees. */
    ARCHIVED
}
