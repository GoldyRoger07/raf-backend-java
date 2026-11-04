package com.raikiservice.raf.notification.repository;

import java.util.Collection;
import java.util.List;

import com.raikiservice.raf.notification.domain.AnnouncementRecipient;
import com.raikiservice.raf.notification.domain.RecipientStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRecipientRepository extends JpaRepository<AnnouncementRecipient, Long> {

    /**
     * Joueurs vises par un envoi a tous : comptes actifs sans role d'equipe
     * (moderateur, admin), comptes de test exclus sauf demande.
     */
    String ALL_PLAYERS = "u.status = 'ACTIVE' AND (:includeTest = TRUE OR u.is_test = FALSE) "
            + "AND NOT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
            + "WHERE ur.user_id = u.id AND r.name <> 'PLAYER')";

    /** Fige les destinataires d'un envoi a tous, en une seule requete. */
    @Modifying
    @Query(value = "INSERT INTO announcement_recipients (announcement_id, user_id, status) "
            + "SELECT :announcementId, u.id, 'PENDING' FROM users u WHERE " + ALL_PLAYERS,
            nativeQuery = true)
    int freezeAllPlayers(@Param("announcementId") Long announcementId,
            @Param("includeTest") boolean includeTest);

    @Query(value = "SELECT COUNT(*) AS total, "
            + "COALESCE(SUM(u.email_verified_at IS NOT NULL), 0) AS withEmail, "
            + "COALESCE(SUM(EXISTS (SELECT 1 FROM push_subscriptions p WHERE p.user_id = u.id)), 0) "
            + "AS withPush FROM users u WHERE " + ALL_PLAYERS, nativeQuery = true)
    AudienceCounts countAllPlayers(@Param("includeTest") boolean includeTest);

    List<AnnouncementRecipient> findByAnnouncementIdAndStatusOrderByIdAsc(Long announcementId,
            RecipientStatus status, Pageable pageable);

    @Query(value = "SELECT r.announcement_id AS announcementId, COUNT(*) AS total, "
            + "SUM(r.status = 'PENDING') AS pending, SUM(r.status = 'DONE') AS delivered, "
            + "SUM(r.status = 'FAILED') AS failed, SUM(r.status = 'SKIPPED') AS skipped, "
            + "SUM(r.email_sent) AS emailSent, SUM(r.push_sent) AS pushSent, "
            + "SUM(n.acknowledged_at IS NOT NULL) AS acknowledged, "
            + "SUM(n.read_at IS NOT NULL) AS opened "
            + "FROM announcement_recipients r LEFT JOIN notifications n ON n.id = r.notification_id "
            + "WHERE r.announcement_id IN (:ids) GROUP BY r.announcement_id", nativeQuery = true)
    List<RecipientStats> stats(@Param("ids") Collection<Long> ids);

    interface AudienceCounts {
        Number getTotal();

        Number getWithEmail();

        Number getWithPush();
    }

    interface RecipientStats {
        Number getAnnouncementId();

        Number getTotal();

        Number getPending();

        Number getDelivered();

        Number getFailed();

        Number getSkipped();

        Number getEmailSent();

        Number getPushSent();

        Number getAcknowledged();

        Number getOpened();
    }
}
