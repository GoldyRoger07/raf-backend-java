package com.raikiservice.raf.notification.mail;

import java.util.Map;

import com.raikiservice.raf.common.config.AppProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Envoi via Resend (https://resend.com).
 *
 * <p>Sans cle API configuree, le message est simplement journalise : le parcours
 * d'inscription reste testable en local sans compte Resend.
 */
@Slf4j
@Component
public class ResendMailSender implements MailSender {

    private static final String ENDPOINT = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final String apiKey;
    private final String from;

    public ResendMailSender(AppProperties properties) {
        this.restClient = RestClient.create();
        this.apiKey = properties.mail().resendApiKey();
        this.from = properties.mail().from();
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("re_CHANGE_ME")) {
            log.info("[EMAIL SIMULE] vers {} : {}\n{}", to, subject, htmlBody);
            return;
        }

        try {
            restClient.post()
                    .uri(ENDPOINT)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of(
                            "from", from,
                            "to", new String[] { to },
                            "subject", subject,
                            "html", htmlBody))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // Un email qui ne part pas ne doit pas faire echouer l'inscription :
            // l'utilisateur peut toujours demander un renvoi.
            log.error("Echec d'envoi de l'email '{}' vers {}", subject, to, e);
        }
    }
}
