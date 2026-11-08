package com.raikiservice.raf.wallet.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TopUpRequestRepository extends JpaRepository<TopUpRequest, Long> {

    Page<TopUpRequest> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<TopUpRequest> findByStatusOrderByCreatedAtDesc(TopUpStatus status, Pageable pageable);

    Optional<TopUpRequest> findByProviderAndProviderRef(PaymentProvider provider, String providerRef);

    /** Serialise webhook, verification planifiee et retour du joueur sur une meme recharge. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopUpRequest t where t.id = :id")
    Optional<TopUpRequest> findByIdForUpdate(@Param("id") Long id);

    long countByUserIdAndProviderInAndStatusAndCreatedAtAfter(Long userId, Collection<PaymentProvider> providers,
            TopUpStatus status, Instant after);

    /** Recharges en ligne en attente a reverifier, les moins recemment verifiees d'abord. */
    @Query("""
            select t.id from TopUpRequest t
            where t.status = com.raikiservice.raf.wallet.domain.TopUpStatus.PENDING
              and t.provider in :providers and t.chargedHtg is not null
              and t.createdAt < :createdBefore
              and (t.checkedAt is null or t.checkedAt < :checkedBefore)
            order by t.checkedAt asc nulls first, t.id asc
            """)
    List<Long> findOnlinePendingToCheck(@Param("providers") Collection<PaymentProvider> providers,
            @Param("createdBefore") Instant createdBefore, @Param("checkedBefore") Instant checkedBefore,
            Pageable pageable);
}
