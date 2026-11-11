package com.raikiservice.raf.wallet.repository;

import java.util.List;

import com.raikiservice.raf.wallet.domain.WithdrawalPayout;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WithdrawalPayoutRepository extends JpaRepository<WithdrawalPayout, Long> {

    List<WithdrawalPayout> findByWithdrawalIdOrderByIdAsc(Long withdrawalId);
}
