package com.raikiservice.raf.notification.web.dto;

import java.time.Instant;

import com.raikiservice.raf.notification.domain.AnnouncementAudience;
import com.raikiservice.raf.notification.domain.AnnouncementStatus;

/** Un message du back-office et le suivi de son envoi. */
public record AnnouncementResponse(
        Long id,
        String title,
        String body,
        String linkPath,
        AnnouncementAudience audience,
        Person target,
        boolean includeTest,
        boolean important,
        boolean popup,
        boolean push,
        boolean email,
        AnnouncementStatus status,
        Instant scheduledAt,
        Instant popupExpiresAt,
        Person createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        Stats stats) {

    public record Person(Long id, String name, String email) {
    }

    /**
     * @param delivered destinataires traites (notification in-app creee)
     * @param skipped comptes supprimes entre-temps
     * @param opened notifications lues (popup acquitte ou notification ouverte)
     */
    public record Stats(long total, long pending, long delivered, long failed, long skipped,
            long emailSent, long pushSent, long acknowledged, long opened) {

        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
