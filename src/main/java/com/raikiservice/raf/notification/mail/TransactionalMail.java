package com.raikiservice.raf.notification.mail;

import com.raikiservice.raf.notification.service.NotificationExecutor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Emails declenches par une requete (verification, mot de passe), envoyes apres
 * le commit sur la file des notifications.
 *
 * <p>Un envoi SMTP peut prendre plusieurs secondes : fait dans la transaction, il
 * immobiliserait une connexion du pool pendant tout ce temps. Et si la transaction
 * est annulee, l'email (avec un jeton jamais enregistre) ne part pas.
 */
@Component
@RequiredArgsConstructor
public class TransactionalMail {

    private final MailSender mailSender;
    private final NotificationExecutor notificationExecutor;

    public void sendAfterCommit(String to, String subject, String htmlBody) {
        notificationExecutor.afterCommit("email '" + subject + "'",
                () -> mailSender.send(to, subject, htmlBody));
    }
}
