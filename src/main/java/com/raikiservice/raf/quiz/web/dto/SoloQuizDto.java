package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Quiz solo : un sujet fixe, un prix par partie, un prix pour un sans-faute.
 * Toujours joue question par question (SEQUENTIAL), sans ticket ni classement.
 */
public record SoloQuizDto(

        @NotBlank(message = "Le titre est obligatoire.")
        @Size(max = 160)
        String title,

        @Size(max = 1000)
        String description,

        /** Doit etre un sujet propose en mode Solo. */
        @NotNull(message = "Le sujet est obligatoire.")
        Long categoryId,

        Long bannerMediaId,

        /** 0 pour un quiz gratuit. */
        @Min(value = 0, message = "Le prix d'une partie ne peut pas etre negatif.")
        int entryPriceCredits,

        /** En HTG : c'est ce qui arrive sur les gains, retirables, du joueur. */
        @Min(value = 1, message = "Le prix d'un sans-faute doit etre positif.")
        long prizeHtg,

        @Min(value = 1, message = "Il faut au moins une question par partie.")
        int questionsPerGame,

        @Min(value = 3, message = "Une question dure au moins 3 secondes.")
        @Max(value = 300, message = "Une question dure au plus 5 minutes.")
        int questionDurationSeconds,

        /**
         * Fermer le quiz des le premier sans-faute. Absent (anciens clients) : non.
         * Les parties deja lancees vont a leur terme et peuvent aussi gagner.
         */
        Boolean closeOnPerfect,

        /**
         * Apres un sans-faute, cacher le quiz a ce joueur pendant ce nombre de minutes.
         * Absent ou vide : regle desactivee.
         */
        @Min(value = 1, message = "Le delai doit etre d'au moins une minute.")
        @Max(value = MAX_COOLDOWN_MINUTES, message = "Le delai ne peut pas depasser un an.")
        Integer perfectCooldownMinutes,

        /**
         * Part des questions faciles / moyennes / difficiles de chaque partie. Absent
         * ou vide : tirage libre dans le vivier.
         */
        @Valid
        DifficultyMixDto difficultyMix) {

    /** Un an. */
    public static final int MAX_COOLDOWN_MINUTES = 525_600;

    public boolean closesOnPerfect() {
        return Boolean.TRUE.equals(closeOnPerfect);
    }
}
