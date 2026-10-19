package com.raikiservice.raf.notification.sms;

/** Envoi d'un SMS transactionnel (codes OTP). Implementation reelle au lot 7. */
public interface SmsSender {

    void send(String phone, String message);
}
