package com.raikiservice.raf.notification.web.dto;

import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationType;

import jakarta.validation.constraints.NotNull;

public record PreferenceUpdateDto(

        @NotNull
        NotificationType type,

        /** EMAIL ou PUSH : l'in-app ne se desactive pas. */
        @NotNull
        NotificationChannel channel,

        boolean enabled) {
}
