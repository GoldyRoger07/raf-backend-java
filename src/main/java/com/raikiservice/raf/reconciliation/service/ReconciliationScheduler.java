package com.raikiservice.raf.reconciliation.service;

import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rapprochement planifie, chaque nuit a 3 h (heure d'Haiti) par defaut :
 * {@code app.reconciliation.cron} et {@code app.reconciliation.zone}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    @Scheduled(cron = "${app.reconciliation.cron:0 0 3 * * *}",
            zone = "${app.reconciliation.zone:America/Port-au-Prince}")
    public void nightly() {
        try {
            reconciliationService.run(ReconciliationTrigger.SCHEDULED, null);
        } catch (RuntimeException e) {
            log.error("Rapprochement planifie impossible", e);
        }
    }
}
