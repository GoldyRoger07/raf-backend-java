package com.raikiservice.raf.wallet.repository;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, Long> {

    Page<WithdrawalRequest> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<WithdrawalRequest> findByStatusOrderByCreatedAtAsc(WithdrawalStatus status, Pageable pageable);

    List<WithdrawalRequest> findByUserIdAndStatus(Long userId, WithdrawalStatus status);

    /**
     * Total deja engage sur la periode, pour le plafond journalier.
     *
     * <p>Les demandes rejetees sont exclues : le joueur a recupere son argent,
     * elles ne doivent pas consommer son plafond.
     */
    @Query("select coalesce(sum(w.amountCredits), 0) from WithdrawalRequest w "
            + "where w.user.id = :userId and w.createdAt >= :since "
            + "and w.status <> com.raikiservice.raf.wallet.domain.WithdrawalStatus.REJECTED")
    long sumRequestedSince(@Param("userId") Long userId, @Param("since") Instant since);
}
