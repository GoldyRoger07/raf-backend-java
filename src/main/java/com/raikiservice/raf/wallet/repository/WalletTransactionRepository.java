package com.raikiservice.raf.wallet.repository;

import java.time.Instant;
import java.util.Optional;

import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);

    /**
     * Historique d'un portefeuille pour le back-office, criteres facultatifs. {@code term}
     * (deja entoure de {@code %}) cherche dans le libelle et la reference. Ordre : celui du
     * Pageable.
     */
    @Query("select t from WalletTransaction t where t.wallet.id = :walletId "
            + "and (:type is null or t.type = :type) "
            + "and (:balanceType is null or t.balanceType = :balanceType) "
            + "and (:from is null or t.createdAt >= :from) "
            + "and (:to is null or t.createdAt < :to) "
            + "and (:term is null or lower(t.description) like :term or lower(t.referenceId) like :term "
            + "  or lower(t.referenceType) like :term)")
    Page<WalletTransaction> adminHistory(@Param("walletId") Long walletId,
            @Param("type") WalletTransactionType type, @Param("balanceType") BalanceType balanceType,
            @Param("from") Instant from, @Param("to") Instant to, @Param("term") String term,
            Pageable pageable);

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
