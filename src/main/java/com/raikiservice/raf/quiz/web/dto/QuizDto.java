package com.raikiservice.raf.quiz.web.dto;

import com.raikiservice.raf.quiz.domain.TopicMode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuizDto(

        @NotBlank(message = "Le titre est obligatoire.")
        @Size(max = 160)
        String title,

        @Size(max = 1000)
        String description,

        /** Annonce avant le vote pour que l'achat du ticket ne soit pas aveugle. */
        @Size(max = 160)
        String themeLabel,

        TopicMode topicMode,

        /** Obligatoire en mode ADMIN, ignore en mode VOTE. */
        Long categoryId,

        Long bannerMediaId,

        @Min(value = 1, message = "Le prix du ticket doit etre positif.")
        int ticketPriceCredits,

        @Min(value = 1, message = "Le seuil de joueurs doit etre positif.")
        int minPlayers,

        @Min(value = 1, message = "Le plafond total doit etre positif.")
        Integer maxTicketsTotal,

        @Min(value = 1, message = "Le plafond par joueur doit etre positif.")
        Integer maxTicketsPerPlayer,

        @Min(value = 0, message = "Le delai de demarrage ne peut pas etre negatif.")
        int startDelayMinutes,

        @Min(value = 10, message = "Une partie dure au moins 10 secondes.")
        @Max(value = 3600, message = "Une partie dure au plus une heure.")
        int gameDurationSeconds,

        @Min(value = 1, message = "Il faut au moins une question par partie.")
        int questionsPerGame,

        @Min(value = 1, message = "Le bareme doit etre positif.")
        int pointsPerCorrect,

        @Min(value = 1, message = "Le score declencheur doit etre positif.")
        int minScoreThreshold,

        @Min(value = 1, message = "Le compte a rebours doit durer au moins une minute.")
        int closingCountdownMinutes) {
}
