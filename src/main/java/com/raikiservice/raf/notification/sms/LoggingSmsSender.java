package com.raikiservice.raf.notification.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Aucun operateur SMS n'est encore branche : le code est journalise pour permettre
 * de tester le parcours de verification en developpement.
 */
@Slf4j
@Component
public class LoggingSmsSender implements SmsSender {

    @Override
    public void send(String phone, String message) {
        log.info("[SMS SIMULE] vers {} : {}", phone, message);
    }
}
