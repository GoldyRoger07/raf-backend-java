package com.raikiservice.raf.notification.repository;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.notification.domain.Notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId,
            Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** Filtre sur le proprietaire : un joueur ne marque que ses propres notifications. */
    @Modifying
    @Query("update Notification n set n.readAt = :now "
            + "where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /** Popups a afficher : non acquittes, non expires, du plus ancien au plus recent. */
    @Query("select n from Notification n where n.userId = :userId and n.requiresAck = true "
            + "and n.acknowledgedAt is null and (n.expiresAt is null or n.expiresAt > :now) "
            + "order by n.createdAt, n.id")
    List<Notification> findPendingPopups(@Param("userId") Long userId, @Param("now") Instant now);

    /** "J'ai compris" : acquitte le popup et le marque lu. */
    @Modifying
    @Query("update Notification n set n.acknowledgedAt = :now, n.readAt = coalesce(n.readAt, :now) "
            + "where n.id = :id and n.userId = :userId and n.requiresAck = true "
            + "and n.acknowledgedAt is null")
    int acknowledge(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from Notification n where n.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
