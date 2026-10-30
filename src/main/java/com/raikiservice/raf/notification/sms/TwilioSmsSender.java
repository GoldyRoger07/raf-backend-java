package com.raikiservice.raf.notification.sms;

import java.net.http.HttpClient;
import java.time.Duration;

import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Envoi par l'API REST de Twilio (Programmable Messaging), actif avec
 * {@code app.sms.mode=twilio}. Appel HTTP direct : le SDK Twilio n'apporterait
 * qu'une dependance de plus pour une seule requete.
 *
 * <p>Sans identifiants ({@code TWILIO_ACCOUNT_SID}, {@code TWILIO_AUTH_TOKEN},
 * {@code TWILIO_FROM}), le SMS est journalise comme avec {@link LoggingSmsSender}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "twilio")
public class TwilioSmsSender implements SmsSender {

    private static final String API_URL = "https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json";

    private final AppProperties.Sms.Twilio twilio;
    private final RestClient restClient;

    public TwilioSmsSender(AppProperties properties) {
        this.twilio = properties.sms().twilio();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        if (!twilio.isConfigured()) {
            log.warn("Mode twilio sans identifiants (TWILIO_*) : les SMS seront journalises, pas envoyes");
        }
    }

    @Override
    public void send(String phone, String message) {
        if (!twilio.isConfigured()) {
            LoggingSmsSender.print(phone, message);
            return;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phone);
        // Un Messaging Service (MG...) choisit lui-meme l'expediteur selon le pays.
        form.add(twilio.from().startsWith("MG") ? "MessagingServiceSid" : "From", twilio.from());
        form.add("Body", message);

        try {
            restClient.post()
                    .uri(API_URL, twilio.accountSid())
                    .headers(headers -> headers.setBasicAuth(twilio.accountSid(), twilio.authToken()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Le corps contient le code Twilio (21211 numero invalide, 20003 cle refusee...).
            log.error("Twilio a refuse le SMS vers {} ({}) : {}", phone, e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw new ApiException(ErrorCode.SMS_SEND_FAILED);
        } catch (ResourceAccessException e) {
            log.error("Twilio injoignable pour le SMS vers {}", phone, e);
            throw new ApiException(ErrorCode.SMS_SEND_FAILED);
        }
    }
}
