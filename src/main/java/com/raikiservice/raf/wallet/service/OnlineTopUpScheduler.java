package com.raikiservice.raf.wallet.service;

import com.raikiservice.raf.wallet.tranzak.TranzakGateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet de securite des recharges en ligne : chaque minute, relit chez Tranzak
 * les recharges restees en attente (webhook perdu, MonCash en mode test qui n'en
 * envoie pas) et fait expirer celles qui ne seront jamais payees.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineTopUpScheduler {

    private final OnlineTopUpService onlineTopUpService;
    private final TranzakGateway gateway;

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
}
