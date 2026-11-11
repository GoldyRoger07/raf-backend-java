package com.raikiservice.raf.wallet.domain;

import com.raikiservice.raf.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Tranche d'un retrait versee a la main par un admin (montant net, reference de l'operateur). */
@Getter
@Setter
@Entity
@Table(name = "withdrawal_payouts")
public class WithdrawalPayout extends BaseEntity {

    @Column(name = "withdrawal_id", nullable = false)
    private Long withdrawalId;

    @Column(name = "amount_htg", nullable = false)
    private long amountHtg;

    @Column(name = "payout_reference", nullable = false, length = 191)
    private String payoutReference;

    @Column(name = "admin_id")
    private Long adminId;
}
