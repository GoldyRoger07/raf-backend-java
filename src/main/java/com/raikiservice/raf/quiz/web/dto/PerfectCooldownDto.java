package com.raikiservice.raf.quiz.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Regle « cache apres un sans-faute » d'un quiz solo.
 *
 * @param minutes delai en minutes ; null pour desactiver la regle
 */
public record PerfectCooldownDto(
        @Min(value = 1, message = "Le delai doit etre d'au moins une minute.")
        @Max(value = SoloQuizDto.MAX_COOLDOWN_MINUTES, message = "Le delai ne peut pas depasser un an.")
        Integer minutes) {
}
