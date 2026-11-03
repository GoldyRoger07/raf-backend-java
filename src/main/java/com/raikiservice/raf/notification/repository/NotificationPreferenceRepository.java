package com.raikiservice.raf.notification.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationPreference;
import com.raikiservice.raf.notification.domain.NotificationType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUserId(Long userId);

    Optional<NotificationPreference> findByUserIdAndTypeAndChannel(Long userId, NotificationType type,
            NotificationChannel channel);

    @Modifying
    @Query("delete from NotificationPreference n where n.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
