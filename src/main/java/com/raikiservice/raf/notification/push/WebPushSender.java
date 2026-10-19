package com.raikiservice.raf.notification.push;

import java.security.GeneralSecurityException;
import java.security.Security;

import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.notification.domain.PushSubscription;

import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

/**
 * Web Push (RFC 8030) signe avec les cles VAPID.
 *
 * <p>Sans cles configurees, le push est journalise au lieu d'etre envoye, comme
 * l'email en mode console : le parcours reste testable sans navigateur abonne.
 */
@Slf4j
@Component
public class WebPushSender implements PushSender {

    private final PushService pushService;

    public WebPushSender(AppProperties properties) {
        AppProperties.Push push = properties.push();
        PushService service = null;
        if (push != null && push.isConfigured()) {
            // Requis par la librairie pour le chiffrement ECDH du contenu.
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            try {
                service = new PushService(push.vapidPublicKey(), push.vapidPrivateKey(),
                        push.vapidSubject());
            } catch (GeneralSecurityException e) {
                log.error("Cles VAPID invalides : les push seront journalises au lieu d'etre envoyes", e);
            }
        } else {
            log.info("Cles VAPID absentes : les push seront journalises au lieu d'etre envoyes");
        }
        this.pushService = service;
    }

    @Override
    public Result send(PushSubscription subscription, String jsonPayload) {
        if (pushService == null) {
            log.info("[PUSH SIMULE] vers l'utilisateur {} : {}", subscription.getUserId(), jsonPayload);
            return Result.SENT;
        }

        try {
            Notification notification = new Notification(subscription.getEndpoint(),
                    subscription.getP256dh(), subscription.getAuthKey(), jsonPayload);
            HttpResponse response = pushService.send(notification);
            int status = response.getStatusLine().getStatusCode();

            if (status == 404 || status == 410) {
                return Result.GONE;
            }
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            log.warn("Push refuse par le service du navigateur ({}) pour l'abonnement {}",
                    status, subscription.getId());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("Echec d'envoi du push a l'abonnement {}", subscription.getId(), e);
            return Result.FAILED;
        }
    }

    public boolean isConfigured() {
        return pushService != null;
    }
}
