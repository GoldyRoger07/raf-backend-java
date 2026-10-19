package com.raikiservice.raf.notification.mail;

import java.util.Map;

import com.raikiservice.raf.common.config.AppProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Envoi via Resend (https://resend.com), actif avec {@code app.mail.mode=resend}.
 *
 * <p>Sans cle API configuree, le message est affiche en console comme avec
 * {@link ConsoleMailSender} : mieux vaut un email visible dans les logs qu'un
 * email perdu.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "resend")
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
            log.warn("Mode resend sans cle API : email affiche en console");
            ConsoleMailSender.print(to, subject, htmlBody);
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
