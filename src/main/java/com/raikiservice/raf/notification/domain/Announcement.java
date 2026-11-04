package com.raikiservice.raf.notification.domain;

import java.time.Instant;

import com.raikiservice.raf.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Message ecrit dans le back-office, envoye a un joueur ou a tous. */
@Getter
@Setter
@Entity
@Table(name = "announcements")
public class Announcement extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    /** Chemin dans l'app (ex. /quiz/12), ou null. */
    @Column(name = "link_path", length = 255)
    private String linkPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementAudience audience;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "include_test", nullable = false)
    private boolean includeTest;

    /** Message de service : ignore les preferences email / push des joueurs. */
    @Column(nullable = false)
    private boolean important;

    @Column(name = "channel_popup", nullable = false)
    private boolean channelPopup;

    @Column(name = "channel_push", nullable = false)
    private boolean channelPush;

    @Column(name = "channel_email", nullable = false)
    private boolean channelEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementStatus status = AnnouncementStatus.SCHEDULED;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "popup_expires_at")
    private Instant popupExpiresAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
