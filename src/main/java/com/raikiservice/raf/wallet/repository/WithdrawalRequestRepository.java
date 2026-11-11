package com.raikiservice.raf.wallet.repository;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.wallet.domain.PaymentProvider;
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

    /**
     * Liste du back-office : statut obligatoire, le reste facultatif et cumulable.
     * {@code term} et {@code phone} arrivent deja entoures de {@code %} ; {@code id} est
     * le numero de demande quand la recherche en est un. L'alias {@code u} (joueur) sert
     * aussi au tri.
     */
    @Query("select w from WithdrawalRequest w join w.user u where (:status is null or w.status = :status "
            + "  or (:status = com.raikiservice.raf.wallet.domain.WithdrawalStatus.APPROVED "
            + "      and w.status = com.raikiservice.raf.wallet.domain.WithdrawalStatus.PARTIALLY_PAID)) "
            + "and (:userId is null or u.id = :userId) "
            + "and (:term is null or lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''))) like :term "
            + "  or lower(u.email) like :term or w.destinationNumber like :term "
            + "  or (:phone is not null and (u.phone like :phone or w.destinationNumber like :phone)) "
            + "  or w.id = :id) "
            + "and (:provider is null or w.provider = :provider) "
            + "and (:counter is null or (:counter = true and w.initiatedByAdminId is not null) "
            + "  or (:counter = false and w.initiatedByAdminId is null)) "
            + "and (:from is null or w.createdAt >= :from) "
            + "and (:to is null or w.createdAt < :to)")
    Page<WithdrawalRequest> adminList(@Param("status") WithdrawalStatus status, @Param("userId") Long userId,
            @Param("term") String term,
            @Param("phone") String phone, @Param("id") Long id,
            @Param("provider") PaymentProvider provider,
            @Param("counter") Boolean counter, @Param("from") Instant from, @Param("to") Instant to,
            Pageable pageable);

    List<WithdrawalRequest> findByUserIdAndStatus(Long userId, WithdrawalStatus status);

    /**
     * Total deja engage sur la periode, pour le plafond journalier.
     *
     * <p>Ne comptent que les demandes ou l'argent a reellement bouge. Sont exclues
     * les demandes rejetees (le joueur a recupere son argent), et les retraits au
     * guichet non confirmes ou annules (rien n'a ete debite).
     */
    @Query("select coalesce(sum(w.amountHtg), 0) from WithdrawalRequest w "
            + "where w.user.id = :userId and w.createdAt >= :since "
            + "and w.status not in (com.raikiservice.raf.wallet.domain.WithdrawalStatus.REJECTED, "
            + "com.raikiservice.raf.wallet.domain.WithdrawalStatus.AWAITING_OTP, "
            + "com.raikiservice.raf.wallet.domain.WithdrawalStatus.CANCELLED)")
    long sumRequestedSince(@Param("userId") Long userId, @Param("since") Instant since);

    Optional<WithdrawalRequest> findByPayoutId(String payoutId);

    /** Envois automatiques en cours a reverifier, les moins recemment verifies d'abord. */
    @Query("""
            select w.id from WithdrawalRequest w
            where w.status = com.raikiservice.raf.wallet.domain.WithdrawalStatus.SENDING
              and w.payoutSentAt < :sentBefore
              and (w.payoutCheckedAt is null or w.payoutCheckedAt < :checkedBefore)
            order by w.payoutCheckedAt asc nulls first, w.id asc
            """)
    List<Long> findSendingToCheck(@Param("sentBefore") Instant sentBefore,
            @Param("checkedBefore") Instant checkedBefore, Pageable pageable);

    /**
     * Verrouille la demande avant sa confirmation : deux validations simultanees
     * (double clic de l'agent) sont serialisees, la seconde retrouve la demande
     * deja payee. Premiere lecture de la demande dans la transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WithdrawalRequest w where w.id = :id")
    Optional<WithdrawalRequest> findByIdForUpdate(@Param("id") Long id);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<WithdrawalStatus> statuses);
}
