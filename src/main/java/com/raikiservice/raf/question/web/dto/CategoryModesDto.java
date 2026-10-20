package com.raikiservice.raf.question.web.dto;

/** Interrupteurs de disponibilite d'un sujet par mode de jeu. Null : inchange. */
public record CategoryModesDto(Boolean groupEnabled, Boolean soloEnabled) {
}
