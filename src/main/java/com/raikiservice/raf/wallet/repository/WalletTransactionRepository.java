package com.raikiservice.raf.wallet.repository;

import java.util.Optional;

import com.raikiservice.raf.wallet.domain.WalletTransaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable);

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
