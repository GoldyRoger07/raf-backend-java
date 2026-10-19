package com.raikiservice.raf.question.web.dto;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;

/**
 * Vue back-office d'une question : elle expose la bonne reponse.
 *
 * <p>Cette representation ne doit jamais etre servie a un joueur. Le lot 6
 * fournira une vue de jeu distincte, sans le drapeau {@code correct}.
 */
public record QuestionResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String text,
        QuestionType type,
        Difficulty difficulty,
        QuestionStatus status,
        String explanation,
        String imageUrl,
        int timesServed,
        int timesCorrect,
        Double successRate,
        List<ChoiceView> choices,
        Instant createdAt) {

    public record ChoiceView(Long id, String text, boolean correct, int position, String imageUrl) {
    }

    public static QuestionResponse from(Question question) {
        double rate = question.successRate();

        return new QuestionResponse(
                question.getId(),
                question.getCategory().getId(),
                question.getCategory().getName(),
                question.getText(),
                question.getType(),
                question.getDifficulty(),
                question.getStatus(),
                question.getExplanation(),
                question.getMedia() == null ? null : question.getMedia().getUrl(),
                question.getTimesServed(),
                question.getTimesCorrect(),
                rate < 0 ? null : rate,
                question.getChoices().stream()
                        .map(choice -> new ChoiceView(
                                choice.getId(),
                                choice.getText(),
                                choice.isCorrect(),
                                choice.getPosition(),
                                choice.getMedia() == null ? null : choice.getMedia().getUrl()))
                        .toList(),
                question.getCreatedAt());
    }
}
