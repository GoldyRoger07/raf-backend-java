package com.raikiservice.raf.notification.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Aucun SMS ne part : le message est journalise pour tester le parcours de
 * verification en developpement ({@code app.sms.mode=console}, defaut). Le
 * profil prod bascule sur Twilio.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "console", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    @Override
    public void send(String phone, String message) {
        print(phone, message);
    }

    static void print(String phone, String message) {
        log.info("[SMS SIMULE] vers {} : {}", phone, message);
    }
}
