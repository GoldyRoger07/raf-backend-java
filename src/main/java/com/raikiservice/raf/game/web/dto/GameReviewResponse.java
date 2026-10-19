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
            boolean correct,
            Long elapsedMs) {
    }

    public record ReviewChoice(Long id, String text, String imageUrl) {
    }
}
