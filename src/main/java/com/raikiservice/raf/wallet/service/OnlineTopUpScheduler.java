package com.raikiservice.raf.wallet.service;

import com.raikiservice.raf.wallet.tranzak.TranzakGateway;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet de securite Tranzak, chaque minute : relit les recharges en ligne restees
 * en attente (webhook perdu, MonCash en mode test qui n'en envoie pas) et fait
 * expirer celles qui ne seront jamais payees ; relit aussi les retraits en cours
 * d'envoi automatique.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineTopUpScheduler {

    private final OnlineTopUpService onlineTopUpService;
    private final TranzakGateway gateway;
    private final AutoPayoutService autoPayoutService;
    private final TranzakPayoutGateway payoutGateway;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void refreshPending() {
        if (!gateway.isConfigured()) {
            return;
        }
        try {
            int checked = onlineTopUpService.refreshPending();
            if (checked > 0) {
                log.debug("{} recharge(s) en ligne reverifiee(s)", checked);
            }
        } catch (RuntimeException e) {
            log.error("Verification des recharges en ligne impossible", e);
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 40_000L)
    public void refreshPayouts() {
        if (!payoutGateway.isConfigured()) {
            return;
        }
        try {
            int checked = autoPayoutService.refreshSending();
            if (checked > 0) {
                log.debug("{} retrait(s) en cours d'envoi reverifie(s)", checked);
            }
        } catch (RuntimeException e) {
            log.error("Verification des retraits automatiques impossible", e);
        }
    }
}
