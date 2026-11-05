package com.raikiservice.raf.auth.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.OtpCodeRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.repository.VerificationTokenRepository;
import com.raikiservice.raf.auth.web.dto.DeleteAccountRequest;
import com.raikiservice.raf.common.audit.AuditAction;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.notification.mail.MailTemplates;
import com.raikiservice.raf.notification.mail.TransactionalMail;
import com.raikiservice.raf.notification.repository.NotificationPreferenceRepository;
import com.raikiservice.raf.notification.repository.NotificationRepository;
import com.raikiservice.raf.notification.repository.PushSubscriptionRepository;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.repository.WalletRepository;
import com.raikiservice.raf.wallet.repository.WithdrawalRequestRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression du compte par son titulaire.
 *
 * <p>Le compte est anonymise, pas efface : recharges, retraits, mouvements du
 * portefeuille, tickets et classements doivent rester pour la comptabilite et le
 * rapprochement. Sont effaces : nom, email, telephone, mot de passe, sessions,
 * jetons et codes, abonnements push, notifications et preferences. L'email et le
 * numero sont liberes : la personne peut se reinscrire avec.
 */
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    /** Statuts ou de l'argent est en route vers le joueur. */
    private static final EnumSet<WithdrawalStatus> WITHDRAWAL_IN_PROGRESS =
            EnumSet.of(WithdrawalStatus.AWAITING_OTP, WithdrawalStatus.PENDING, WithdrawalStatus.APPROVED);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final VerificationTokenRepository verificationTokenRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final WalletRepository walletRepository;
    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final AuditService auditService;
    private final TransactionalMail transactionalMail;

    @Transactional
    public void deleteOwnAccount(Long userId, DeleteAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETED);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        // Un administrateur supprime par erreur priverait l'equipe d'un acces, voire
        // du dernier super-administrateur : ses roles sont retires d'abord.
        if (user.getRoles().stream().anyMatch(role -> role.getName().isStaff())) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETION_STAFF);
        }
        if (withdrawalRequestRepository.existsByUserIdAndStatusIn(userId, WITHDRAWAL_IN_PROGRESS)) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETION_WITHDRAWAL_PENDING);
        }
        boolean hasBalance = walletRepository.findByUserId(userId)
                .map(wallet -> wallet.getCreditBalance() != 0 || wallet.getWinningsBalance() != 0)
                .orElse(false);
        if (hasBalance && !request.forfeitBalance()) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETION_BALANCE);
        }

        String formerEmail = user.getEmail();
        String formerFirstName = user.getFirstName();

        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(Instant.now());
        user.setEmail("supprime-" + userId + "@compte-supprime.invalid");
        user.setPhone("supprime-" + userId);
        user.setFirstName("Compte");
        user.setLastName("supprime");
        // Mot de passe aleatoire jamais communique : plus aucune connexion possible.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setEmailVerifiedAt(null);
        user.setPhoneVerifiedAt(null);

        refreshTokenService.revokeAllAfterAccountDeletion(userId);
        verificationTokenRepository.deleteByUser(userId);
        otpCodeRepository.deleteByUser(userId);
        pushSubscriptionRepository.deleteByUser(userId);
        notificationRepository.deleteByUser(userId);
        notificationPreferenceRepository.deleteByUser(userId);

        auditService.record(userId, AuditAction.ACCOUNT_DELETED, "User", String.valueOf(userId));
        transactionalMail.sendAfterCommit(formerEmail, "Votre compte a ete supprime - RAF",
                MailTemplates.accountDeleted(formerFirstName));
    }
}
