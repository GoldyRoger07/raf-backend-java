package com.raikiservice.raf.notification.mail;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import com.raikiservice.raf.common.config.AppProperties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Envoi par SMTP avec Spring Mail, actif avec {@code app.mail.mode=smtp}. Le
 * serveur se configure par les proprietes {@code spring.mail.*}.
 *
 * <p>Sans hote SMTP configure ({@code spring.mail.host} vide, donc aucun
 * {@link JavaMailSender}), le message est affiche en console comme avec
 * {@link ConsoleMailSender} : mieux vaut un email visible dans les logs qu'un
 * email perdu.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp")
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;
    private final String fromName;

    public SmtpMailSender(ObjectProvider<JavaMailSender> javaMailSender, AppProperties properties) {
        this.javaMailSender = javaMailSender.getIfAvailable();
        this.from = properties.mail().from();
        this.fromName = properties.mail().fromName();
        if (this.javaMailSender == null) {
            log.warn("Mode smtp sans serveur (MAIL_HOST vide) : les emails seront affiches en console");
        }
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        if (javaMailSender == null) {
            ConsoleMailSender.print(to, subject, htmlBody);
            return;
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            if (fromName == null || fromName.isBlank()) {
                helper.setFrom(from);
            } else {
                helper.setFrom(from, fromName);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            javaMailSender.send(message);
        } catch (MessagingException | MailException | UnsupportedEncodingException e) {
            // Un email qui ne part pas ne doit pas faire echouer l'inscription :
            // l'utilisateur peut toujours demander un renvoi.
            log.error("Echec d'envoi de l'email '{}' vers {}", subject, to, e);
        }
    }
}
