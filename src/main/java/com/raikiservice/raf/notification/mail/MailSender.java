package com.raikiservice.raf.notification.mail;

/** Envoi d'un email transactionnel. Implementation reelle au lot 7. */
public interface MailSender {

    void send(String to, String subject, String htmlBody);
}
