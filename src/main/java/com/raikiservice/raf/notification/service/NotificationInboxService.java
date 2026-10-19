package com.raikiservice.raf.notification.service;

import java.time.Instant;

import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.util.Hashes;
import com.raikiservice.raf.notification.domain.PushSubscription;
import com.raikiservice.raf.notification.repository.NotificationRepository;
import com.raikiservice.raf.notification.repository.PushSubscriptionRepository;
import com.raikiservice.raf.notification.web.dto.NotificationResponse;
import com.raikiservice.raf.notification.web.dto.PushSubscriptionDto;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ce que le joueur fait de ses notifications : les lire, s'abonner au push. */
@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(Long userId, boolean unreadOnly, Pageable pageable) {
        return (unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable))
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /** Une notification d'un autre joueur est traitee comme inexistante. */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        if (notificationRepository.markRead(notificationId, userId, Instant.now()) == 0
                && notificationRepository.findById(notificationId)
                        .filter(n -> n.getUserId().equals(userId)).isEmpty()) {
            throw ApiException.notFound("Notification");
        }
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, Instant.now());
    }

    /** Cle publique VAPID, dont le navigateur a besoin pour s'abonner. Null si non configuree. */
    public String vapidPublicKey() {
        AppProperties.Push push = appProperties.push();
        return push != null && push.isConfigured() ? push.vapidPublicKey() : null;
    }

    /**
     * Enregistre l'abonnement d'un navigateur. Un endpoint deja connu change de
     * proprietaire : c'est le compte connecte dans ce navigateur qui doit recevoir
     * les push, pas celui qui s'y etait connecte avant lui.
     */
    @Transactional
    public void subscribe(Long userId, PushSubscriptionDto request, String userAgent) {
        String hash = Hashes.sha256Hex(request.endpoint());
        PushSubscription subscription = pushSubscriptionRepository.findByEndpointHash(hash)
                .orElseGet(PushSubscription::new);

        subscription.setUserId(userId);
        subscription.setEndpoint(request.endpoint());
        subscription.setEndpointHash(hash);
        subscription.setP256dh(request.keys().p256dh());
        subscription.setAuthKey(request.keys().auth());
        subscription.setUserAgent(userAgent);
        pushSubscriptionRepository.save(subscription);
    }

    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        pushSubscriptionRepository.deleteByEndpointHashAndUserId(Hashes.sha256Hex(endpoint), userId);
    }
}
