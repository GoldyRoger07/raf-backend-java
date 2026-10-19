package com.raikiservice.raf.wallet.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, Long> {

    Page<WithdrawalRequest> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<WithdrawalRequest> findByStatusOrderByCreatedAtAsc(WithdrawalStatus status, Pageable pageable);

    List<WithdrawalRequest> findByUserIdAndStatus(Long userId, WithdrawalStatus status);

    /**
     * Total deja engage sur la periode, pour le plafond journalier.
     *
     * <p>Ne comptent que les demandes ou l'argent a reellement bouge. Sont exclues
     * les demandes rejetees (le joueur a recupere son argent), et les retraits au
     * guichet non confirmes ou annules (rien n'a ete debite).
     */
    @Query("select coalesce(sum(w.amountCredits), 0) from WithdrawalRequest w "
            + "where w.user.id = :userId and w.createdAt >= :since "
            + "and w.status not in (com.raikiservice.raf.wallet.domain.WithdrawalStatus.REJECTED, "
            + "com.raikiservice.raf.wallet.domain.WithdrawalStatus.AWAITING_OTP, "
            + "com.raikiservice.raf.wallet.domain.WithdrawalStatus.CANCELLED)")
    long sumRequestedSince(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * Verrouille la demande avant sa confirmation : deux validations simultanees
     * (double clic de l'agent) sont serialisees, la seconde retrouve la demande
     * deja payee. Premiere lecture de la demande dans la transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WithdrawalRequest w where w.id = :id")
    Optional<WithdrawalRequest> findByIdForUpdate(@Param("id") Long id);
}
