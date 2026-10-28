package com.raikiservice.raf.notification.mail;

/** Envoi d'un email transactionnel : console ({@link ConsoleMailSender}) ou SMTP selon app.mail.mode. */
public interface MailSender {

    void send(String to, String subject, String htmlBody);
}
