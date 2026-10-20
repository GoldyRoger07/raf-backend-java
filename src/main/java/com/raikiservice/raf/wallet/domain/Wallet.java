package com.raikiservice.raf.wallet.domain;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Portefeuille d'un joueur : le solde de jeu en credits, le solde de gains en
 * gourdes (entiers tous les deux).
 *
 * <p>Les deux soldes sont volontairement separes : voir {@link BalanceType}.
 * Toute modification passe par le service, sous verrou pessimiste, et laisse une
 * ligne dans wallet_transactions.
 */
@Getter
@Setter
@Entity
@Table(name = "wallets")
public class Wallet extends AuditableEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "credit_balance", nullable = false)
    private long creditBalance;

    /** En HTG. */
    @Column(name = "winnings_balance", nullable = false)
    private long winningsBalance;

    public long balanceOf(BalanceType type) {
        return type == BalanceType.CREDIT ? creditBalance : winningsBalance;
    }

    public void setBalanceOf(BalanceType type, long value) {
        if (type == BalanceType.CREDIT) {
            creditBalance = value;
        } else {
            winningsBalance = value;
        }
    }

}
