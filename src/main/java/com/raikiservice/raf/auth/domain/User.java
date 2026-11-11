package com.raikiservice.raf.auth.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.raikiservice.raf.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(nullable = false, length = 180, unique = true)
    private String email;

    /** Format E.164, ex. +50912345678. Sert de cible MonCash / NatCash. */
    @Column(nullable = false, length = 20, unique = true)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Obligatoire avant tout retrait : voir WalletService au lot 3. */
    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    /** Version acceptee de l'accord de paiement par tranches (PayoutTermsService), null sinon. */
    @Column(name = "payout_terms_version")
    private Integer payoutTermsVersion;

    @Column(name = "payout_terms_accepted_at")
    private Instant payoutTermsAcceptedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Compte de test : son activite est exclue du tableau de bord, il voit les quiz de test. */
    @Column(name = "is_test", nullable = false)
    private boolean test;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }
}
