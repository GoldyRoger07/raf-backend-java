package com.raikiservice.raf.notification.web.dto;

import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationType;

public record PreferenceView(NotificationType type, String description, NotificationChannel channel,
        boolean enabled, boolean enabledByDefault) {
}
