package com.raikiservice.raf.notification.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.notification.domain.Notification;
import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.PushSubscription;
import com.raikiservice.raf.notification.mail.MailSender;
import com.raikiservice.raf.notification.mail.MailTemplates;
import com.raikiservice.raf.notification.push.PushSender;
import com.raikiservice.raf.notification.repository.NotificationRepository;
import com.raikiservice.raf.notification.repository.PushSubscriptionRepository;
import com.raikiservice.raf.notification.service.NotificationMessage.Delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Remise d'une notification a un joueur, sur le thread des notifications.
 *
 * <p>Volontairement sans transaction englobante : chaque lecture ou ecriture en
 * base est une transaction courte, et aucune connexion n'est tenue pendant les
 * appels reseau (serveur SMTP, service push), qui peuvent durer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDelivery {

    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationPreferenceService preferenceService;
    private final UserRepository userRepository;
    private final MailSender mailSender;
    private final PushSender pushSender;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public void deliver(Delivery delivery) {
        deliver(delivery, DeliveryOptions.PREFERENCES);
    }

    /**
     * Enregistre la notification in-app puis l'envoie par email et push selon
     * {@code options}. Renvoie ce qui a ete fait, pour le suivi des annonces.
     */
    public DeliveryResult deliver(Delivery delivery, DeliveryOptions options) {
        Long userId = delivery.userId();
        NotificationMessage message = delivery.message();
        // Compte supprime entre l'evenement et l'envoi (ou gain credite apres coup) :
        // plus de boite de reception, d'email ni de push.
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() == UserStatus.DELETED) {
            return DeliveryResult.SKIPPED;
        }

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(message.type());
        notification.setTitle(message.title());
        notification.setBody(message.body());
        notification.setData(message.data().isEmpty() ? null
                : objectMapper.writeValueAsString(message.data()));
        notification.setRequiresAck(options.requiresAck());
        notification.setExpiresAt(options.expiresAt());
        notificationRepository.save(notification);

        Set<NotificationChannel> channels = options.channels() != null ? options.channels()
                : preferenceService.enabledChannels(userId, message.type());
        String link = message.path() == null ? null : appProperties.frontendUrl() + message.path();

        boolean emailSent = false;
        boolean pushSent = false;
        if (channels.contains(NotificationChannel.EMAIL)) {
            sendEmail(user, message, link);
            emailSent = true;
        }
        if (channels.contains(NotificationChannel.PUSH)) {
            pushSent = sendPush(userId, notification.getId(), message, link);
        }
        return new DeliveryResult(notification.getId(), emailSent, pushSent);
    }

    /**
     * @param channels canaux email / push imposes, ou null pour suivre les preferences
     *                 du joueur et les canaux par defaut du type
     * @param requiresAck afficher la notification en popup a acquitter
     * @param expiresAt fin d'affichage du popup (null : jusqu'a l'acquittement)
     */
    public record DeliveryOptions(Set<NotificationChannel> channels, boolean requiresAck,
            Instant expiresAt) {

        public static final DeliveryOptions PREFERENCES = new DeliveryOptions(null, false, null);
    }

    /**
     * @param notificationId notification in-app creee, null si le compte n'existe plus
     * @param pushSent au moins un navigateur du joueur a accepte le push
     */
    public record DeliveryResult(Long notificationId, boolean emailSent, boolean pushSent) {

        public static final DeliveryResult SKIPPED = new DeliveryResult(null, false, false);

        public boolean skipped() {
            return notificationId == null;
        }
    }

    private void sendEmail(User user, NotificationMessage message, String link) {
        mailSender.send(user.getEmail(), message.title() + " - RAF",
                MailTemplates.notification(user.getFirstName(), message.title(), message.body(),
                        link == null ? null : "Voir dans RAF", link));
    }

    private boolean sendPush(Long userId, Long notificationId, NotificationMessage message, String link) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", notificationId);
        payload.put("type", message.type().name());
        payload.put("title", message.title());
        payload.put("body", message.body());
        if (link != null) {
            payload.put("url", link);
        }
        payload.put("data", message.data());
        String json = objectMapper.writeValueAsString(payload);

        boolean sent = false;
        for (PushSubscription subscription : pushSubscriptionRepository.findByUserId(userId)) {
            switch (pushSender.send(subscription, json)) {
                case SENT -> {
                    sent = true;
                    subscription.setLastUsedAt(Instant.now());
                    pushSubscriptionRepository.save(subscription);
                }
                // Le navigateur a revoque l'abonnement : inutile de reessayer.
                case GONE -> pushSubscriptionRepository.delete(subscription);
                case FAILED -> log.debug("Push non remis a l'abonnement {}", subscription.getId());
            }
        }
        return sent;
    }
}
