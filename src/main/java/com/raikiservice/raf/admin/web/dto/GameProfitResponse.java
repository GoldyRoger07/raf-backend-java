package com.raikiservice.raf.admin.web.dto;

import java.time.Instant;

import com.raikiservice.raf.admin.service.GameProfitService.Period;

/**
 * Resultat du jeu sur une periode, en HTG (ventes et remboursements aussi en
 * credits, convertis au taux {@code htgRate} du moment).
 *
 * <p>{@code resultHtg = salesHtg - refundsHtg - prizesHtg - leaderCommissionsHtg - inviteBonusesHtg
 * + withdrawalFeesHtg}.
 * {@code from} est null pour « depuis le lancement ».
 */
public record GameProfitResponse(
        Period period,
        Instant from,
        Instant to,
        long htgRate,
        long salesCredits,
        long salesHtg,
        long refundsCredits,
        long refundsHtg,
        long prizesHtg,
        /** Commissions versees aux leaders sur les parties perdues de leurs joueurs. */
        long leaderCommissionsHtg,
        /** Credits offerts aux joueurs inscrits avec le lien d'un leader, et leur valeur au taux du moment. */
        long inviteBonusesCredits,
        long inviteBonusesHtg,
        long withdrawalFeesHtg,
        long resultHtg,
        long ticketsSold,
        long soloGames) {
}
