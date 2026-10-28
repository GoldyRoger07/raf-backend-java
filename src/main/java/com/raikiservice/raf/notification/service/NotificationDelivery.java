package com.raikiservice.raf.notification.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.raikiservice.raf.auth.domain.User;
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
        Long userId = delivery.userId();
        NotificationMessage message = delivery.message();

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(message.type());
        notification.setTitle(message.title());
        notification.setBody(message.body());
        notification.setData(message.data().isEmpty() ? null
                : objectMapper.writeValueAsString(message.data()));
        notificationRepository.save(notification);

        Set<NotificationChannel> channels = preferenceService.enabledChannels(userId, message.type());
        String link = message.path() == null ? null : appProperties.frontendUrl() + message.path();

        if (channels.contains(NotificationChannel.EMAIL)) {
            userRepository.findById(userId).ifPresent(user -> sendEmail(user, message, link));
        }
        if (channels.contains(NotificationChannel.PUSH)) {
            sendPush(userId, notification.getId(), message, link);
        }
    }

    private void sendEmail(User user, NotificationMessage message, String link) {
        mailSender.send(user.getEmail(), message.title() + " - RAF",
                MailTemplates.notification(user.getFirstName(), message.title(), message.body(),
                        link == null ? null : "Voir dans RAF", link));
    }

    private void sendPush(Long userId, Long notificationId, NotificationMessage message, String link) {
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

        for (PushSubscription subscription : pushSubscriptionRepository.findByUserId(userId)) {
            switch (pushSender.send(subscription, json)) {
                case SENT -> {
                    subscription.setLastUsedAt(Instant.now());
                    pushSubscriptionRepository.save(subscription);
                }
                // Le navigateur a revoque l'abonnement : inutile de reessayer.
                case GONE -> pushSubscriptionRepository.delete(subscription);
                case FAILED -> log.debug("Push non remis a l'abonnement {}", subscription.getId());
            }
        }
    }
}
