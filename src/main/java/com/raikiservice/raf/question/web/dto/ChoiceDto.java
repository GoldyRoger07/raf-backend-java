package com.raikiservice.raf.question.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Proposition de reponse en entree. Le drapeau correct ne ressort jamais cote joueur. */
public record ChoiceDto(

        @NotBlank(message = "Le texte de la reponse est obligatoire.")
        @Size(max = 500)
        String text,

        boolean correct,

        Long mediaId) {
}
