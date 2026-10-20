package com.raikiservice.raf.question.ai;

import java.util.List;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionType;

/**
 * Demande de generation pour une categorie.
 *
 * @param difficulty   null = repartition melangee
 * @param type         null = surtout des QCM, quelques vrai / faux
 * @param instructions consignes libres de l'administrateur (theme, epoque...), ou null
 * @param avoid        enonces deja dans la banque, a ne pas reproduire
 */
public record GenerationRequest(
        String categoryName,
        String categoryDescription,
        int count,
        Difficulty difficulty,
        QuestionType type,
        String instructions,
        List<String> avoid) {
}
