package com.raikiservice.raf.wallet.repository;

import java.util.Optional;

import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopUpRequestRepository extends JpaRepository<TopUpRequest, Long> {

    Page<TopUpRequest> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<TopUpRequest> findByStatusOrderByCreatedAtDesc(TopUpStatus status, Pageable pageable);

    Optional<TopUpRequest> findByProviderAndProviderRef(PaymentProvider provider, String providerRef);
}
