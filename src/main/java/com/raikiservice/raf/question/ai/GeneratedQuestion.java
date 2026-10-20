package com.raikiservice.raf.question.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Question telle que le modele l'a produite, sans aucune garantie : les champs
 * restent du texte brut et sont verifies avant tout enregistrement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeneratedQuestion(
        String text,
        String type,
        String difficulty,
        List<Choice> choices,
        String explanation) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(String text, Boolean correct) {
    }

    /** Enveloppe commune aux reponses : {@code {"questions": [...]}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Batch(List<GeneratedQuestion> questions) {
    }
}
