package com.raikiservice.raf.game.web.dto;

import java.util.List;

import com.raikiservice.raf.game.domain.GameSessionStatus;

/** Corrige detaille d'une partie, disponible seulement une fois le quiz termine. */
public record GameReviewResponse(
        Long sessionId,
        Long quizId,
        GameSessionStatus status,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        /** Partie touchee par l'anti-fraude au lancement (solo). */
        boolean antiFraud,
        /** Vraie correction, avant l'anti-fraude : egale a correctCount si rien n'a ete fausse. */
        Integer realCorrectCount,
        Integer realScore,
        List<ReviewItem> items) {

    public record ReviewItem(
            int position,
            Long questionId,
            String text,
            String imageUrl,
            String explanation,
            List<ReviewChoice> choices,
            Long correctChoiceId,
            Long chosenChoiceId,
            /** Tel que compte au joueur (faux si l'anti-fraude l'a fausse). */
            boolean correct,
            Long elapsedMs,
            /** Reponse juste comptee fausse par l'anti-fraude. */
            boolean antiFraudVoided) {
    }

    public record ReviewChoice(Long id, String text, String imageUrl) {
    }
}
