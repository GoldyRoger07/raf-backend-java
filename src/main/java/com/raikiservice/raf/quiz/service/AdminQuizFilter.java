package com.raikiservice.raf.quiz.service;

/**
 * Criteres facultatifs de la liste des quiz du back-office, en plus du statut et du mode.
 *
 * @param query texte cherche dans le titre et le theme
 * @param categoryId sujet arrete du quiz
 * @param free {@code true} pour les quiz gratuits, {@code false} pour les payants
 * @param test {@code true} pour les quiz de test seulement, {@code false} pour les exclure
 */
public record AdminQuizFilter(String query, Long categoryId, Boolean free, Boolean test) {

    public static final AdminQuizFilter NONE = new AdminQuizFilter(null, null, null, null);
}
