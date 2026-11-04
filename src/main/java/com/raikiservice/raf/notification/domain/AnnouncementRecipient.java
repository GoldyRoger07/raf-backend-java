package com.raikiservice.raf.notification.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Un destinataire d'une annonce et ce qui lui a ete envoye. */
@Getter
@Setter
@Entity
@Table(name = "announcement_recipients")
public class AnnouncementRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecipientStatus status = RecipientStatus.PENDING;

    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "email_sent", nullable = false)
    private boolean emailSent;

    @Column(name = "push_sent", nullable = false)
    private boolean pushSent;

    @Column(name = "processed_at")
    private Instant processedAt;
}
