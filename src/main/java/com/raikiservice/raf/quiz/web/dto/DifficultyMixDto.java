package com.raikiservice.raf.quiz.web.dto;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.DifficultyMix;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Repartition par difficulte des questions d'une partie solo, en pourcentages.
 * Les trois vides : tirage libre. Sinon les trois sont remplis et font 100.
 */
public record DifficultyMixDto(
        @Min(value = 0, message = "Un pourcentage ne peut pas etre negatif.")
        @Max(value = 100, message = "Un pourcentage ne peut pas depasser 100.")
        Integer easyPercent,

        @Min(value = 0, message = "Un pourcentage ne peut pas etre negatif.")
        @Max(value = 100, message = "Un pourcentage ne peut pas depasser 100.")
        Integer mediumPercent,

        @Min(value = 0, message = "Un pourcentage ne peut pas etre negatif.")
        @Max(value = 100, message = "Un pourcentage ne peut pas depasser 100.")
        Integer hardPercent) {

    public static DifficultyMixDto from(DifficultyMix mix) {
        return mix == null ? null
                : new DifficultyMixDto(mix.easyPercent(), mix.mediumPercent(), mix.hardPercent());
    }

    /** Null si les trois sont vides ; refuse un melange de vides et de valeurs ou un total different de 100. */
    public DifficultyMix toMix() {
        return toMix(easyPercent, mediumPercent, hardPercent);
    }

    public static DifficultyMix toMix(Integer easy, Integer medium, Integer hard) {
        if (easy == null && medium == null && hard == null) {
            return null;
        }
        if (easy == null || medium == null || hard == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Renseignez les trois pourcentages (facile, moyen, difficile), ou aucun.");
        }
        if (easy < 0 || medium < 0 || hard < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Un pourcentage ne peut pas etre negatif.");
        }
        if (easy + medium + hard != 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Les pourcentages doivent faire 100 au total (actuellement " + (easy + medium + hard) + ").");
        }
        return new DifficultyMix(easy, medium, hard);
    }
}
