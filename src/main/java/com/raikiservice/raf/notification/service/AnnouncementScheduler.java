package com.raikiservice.raf.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reveille l'envoi des annonces : depart des messages programmes, reprise d'un
 * envoi interrompu par un redemarrage. Le travail lui-meme tourne sur le thread de
 * {@link AnnouncementDispatcher}, pas sur celui des ordonnanceurs.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AnnouncementScheduler {

    private final AnnouncementDispatcher dispatcher;

    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void tick() {
        dispatcher.trigger();
    }
}
