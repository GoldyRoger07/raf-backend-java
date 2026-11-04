package com.raikiservice.raf.notification.repository;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.notification.domain.Announcement;
import com.raikiservice.raf.notification.domain.AnnouncementStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Page<Announcement> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Query("select a.id from Announcement a where a.status = "
            + "com.raikiservice.raf.notification.domain.AnnouncementStatus.SCHEDULED "
            + "and a.scheduledAt <= :now order by a.scheduledAt, a.id")
    List<Long> findDueIds(@Param("now") Instant now);

    @Query("select a.id from Announcement a where a.status = "
            + "com.raikiservice.raf.notification.domain.AnnouncementStatus.SENDING "
            + "order by a.startedAt, a.id")
    List<Long> findSendingIds();

    /**
     * Changement d'etat conditionnel : l'envoi et l'annulation d'une annonce
     * programmee se disputent la meme ligne, un seul des deux l'emporte.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Announcement a set a.status = :to, a.startedAt = :now "
            + "where a.id = :id and a.status = :from")
    int start(@Param("id") Long id, @Param("from") AnnouncementStatus from,
            @Param("to") AnnouncementStatus to, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("update Announcement a set a.status = "
            + "com.raikiservice.raf.notification.domain.AnnouncementStatus.CANCELLED, "
            + "a.cancelledBy = :actorId, a.cancelledAt = :now where a.id = :id and a.status = "
            + "com.raikiservice.raf.notification.domain.AnnouncementStatus.SCHEDULED")
    int cancel(@Param("id") Long id, @Param("actorId") Long actorId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("update Announcement a set a.status = "
            + "com.raikiservice.raf.notification.domain.AnnouncementStatus.SENT, "
            + "a.completedAt = :now where a.id = :id and a.status = "
            + "com.raikiservice.raf.notification.domain.AnnouncementStatus.SENDING")
    int complete(@Param("id") Long id, @Param("now") Instant now);
}
