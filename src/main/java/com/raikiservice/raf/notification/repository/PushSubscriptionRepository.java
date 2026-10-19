package com.raikiservice.raf.notification.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.notification.domain.PushSubscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByUserId(Long userId);

    Optional<PushSubscription> findByEndpointHash(String endpointHash);

    @Modifying
    @Query("delete from PushSubscription s where s.endpointHash = :hash and s.userId = :userId")
    int deleteByEndpointHashAndUserId(@Param("hash") String hash, @Param("userId") Long userId);
}
