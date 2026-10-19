package com.raikiservice.raf.notification.web.dto;

import java.time.Instant;

import com.raikiservice.raf.notification.domain.Notification;
import com.raikiservice.raf.notification.domain.NotificationType;

/** {@code data} est la chaine JSON telle qu'enregistree, a interpreter par le frontend. */
public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        String data,
        boolean read,
        Instant createdAt,
        Instant readAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getData(),
                notification.getReadAt() != null,
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
