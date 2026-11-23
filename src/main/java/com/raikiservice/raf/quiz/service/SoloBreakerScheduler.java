package com.raikiservice.raf.quiz.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Disjoncteur de rentabilite des quiz solo, chaque minute ({@link SoloBreakerService}).
 * Meme limite que les autres planificateurs : instance unique supposee. Deux verifications
 * simultanees ne masquent un quiz qu'une fois (verrou du quiz, quiz deja masque ignore).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoloBreakerScheduler {

    private final SoloBreakerService soloBreakerService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 45_000L)
    public void check() {
        try {
            SoloBreakerService.CheckResult result = soloBreakerService.checkAll();
            if (!result.trippedQuizIds().isEmpty()) {
                log.warn("Disjoncteur solo : quiz masque(s) {}", result.trippedQuizIds());
            }
        } catch (RuntimeException e) {
            log.error("Disjoncteur solo : verification impossible", e);
        }
    }
}
