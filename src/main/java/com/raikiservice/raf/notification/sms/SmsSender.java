package com.raikiservice.raf.notification.sms;

/**
 * Envoi d'un SMS transactionnel (codes OTP). Une erreur d'envoi leve une
 * {@code ApiException} {@code SMS_SEND_FAILED} : le code n'est pas enregistre et
 * le joueur peut redemander aussitot, au lieu d'attendre un SMS qui n'arrivera pas.
 */
public interface SmsSender {

    void send(String phone, String message);
}
