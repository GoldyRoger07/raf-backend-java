package com.raikiservice.raf.question.service;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionSource;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;

/**
 * Criteres de la liste des questions du back-office. Tous facultatifs et cumulables ;
 * un critere absent ne filtre pas.
 *
 * @param query texte cherche dans l'enonce
 * @param usage frequence de service : jamais servie, servie, ou au taux de reussite suspect
 */
public record QuestionFilter(
        String query,
        QuestionStatus status,
        Long categoryId,
        QuestionSource source,
        Difficulty difficulty,
        QuestionType type,
        Usage usage) {

    public enum Usage {
        /** Jamais proposee en partie. */
        NEVER_SERVED,
        /** Deja proposee au moins une fois. */
        SERVED,
        /** Servie au moins {@link #SUSPICIOUS_MIN_SERVED} fois et reussie dans 95 % des cas ou plus. */
        SUSPICIOUS
    }

    /** Seuils du signal de fuite, affiche aussi en rouge dans la liste. */
    public static final int SUSPICIOUS_MIN_SERVED = 20;
    public static final double SUSPICIOUS_MIN_RATE = 0.95;

    public static QuestionFilter of(QuestionStatus status, Long categoryId, QuestionSource source) {
        return new QuestionFilter(null, status, categoryId, source, null, null, null);
    }
}
