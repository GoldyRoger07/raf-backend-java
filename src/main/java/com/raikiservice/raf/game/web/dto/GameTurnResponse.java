package com.raikiservice.raf.game.web.dto;

/**
 * Suite d'une partie SEQUENTIAL apres une reponse.
 *
 * <p>Comme au submit groupe, rien n'indique si la reponse etait juste : le
 * corrige n'est revele qu'a la cloture du quiz.
 *
 * @param accepted false si la reponse n'a pas ete retenue : arrivee apres le
 *                 chrono (la question compte alors sans reponse) ou portant sur
 *                 une autre question que celle en cours (envoi rejoue)
 * @param next     la question suivante, null une fois la partie terminee
 * @param result   le resultat, renseigne une fois la partie terminee
 */
public record GameTurnResponse(
        Long sessionId,
        boolean accepted,
        int answeredQuestions,
        int totalQuestions,
        GameStartResponse.CurrentQuestion next,
        GameResultResponse result) {
}
