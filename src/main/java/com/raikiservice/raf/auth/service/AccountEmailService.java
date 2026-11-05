package com.raikiservice.raf.auth.service;

import java.time.Duration;
import java.time.Instant;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.domain.VerificationTokenType;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.repository.VerificationTokenRepository;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.common.audit.AuditAction;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.ratelimit.RateLimiter;
import com.raikiservice.raf.notification.mail.MailTemplates;
import com.raikiservice.raf.notification.mail.TransactionalMail;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correction de l'adresse email d'un compte.
 *
 * <p>Cas vise : une faute de frappe a l'inscription. Le lien de verification part
 * vers une adresse que la personne ne lit pas, elle ne peut ni jouer ni se
 * reinscrire (son telephone est deja pris). Deux voies :
 * <ul>
 *   <li>le joueur lui-meme, tant que son adresse n'est pas verifiee (une adresse
 *       verifiee ne se change pas depuis l'app : ce serait la porte d'un vol de
 *       compte, le mot de passe oublie partant vers la nouvelle adresse) ;</li>
 *   <li>un agent depuis le back-office, verifiee ou non ; le compte d'un membre
 *       du staff, seulement par un super-administrateur.</li>
 * </ul>
 * Dans les deux cas l'adresse repasse non verifiee et un nouveau lien part ; les
 * liens encore actifs (verification, mot de passe oublie) vers l'ancienne adresse
 * sont invalides.
 */
@Service
@RequiredArgsConstructor
public class AccountEmailService {

    private static final int MAX_CHANGES_PER_ACCOUNT = 3;
    private static final int MAX_CHANGES_PER_IP = 20;
    private static final Duration CHANGE_WINDOW = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationService verificationService;
    private final VerificationTokenRepository verificationTokenRepository;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final TransactionalMail transactionalMail;

    /** Le joueur corrige son adresse, encore non verifiee. */
    @Transactional
    public UserResponse correctOwnEmail(Long userId, String password, String rawEmail, String clientIp) {
        User user = load(userId);
        if (user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED,
                    "Votre adresse est deja verifiee : contactez le support pour la changer.");
        }
        if (password == null || password.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Le mot de passe est obligatoire.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }
        // Chaque correction envoie un email : plafonnee comme les renvois de lien.
        String accountKey = "email-change:user:" + userId;
        String ipKey = "email-change:ip:" + clientIp;
        rateLimiter.assertNotBlocked(accountKey, MAX_CHANGES_PER_ACCOUNT);
        rateLimiter.assertNotBlocked(ipKey, MAX_CHANGES_PER_IP);

        String previous = user.getEmail();
        apply(user, rawEmail);
        rateLimiter.recordAttempt(accountKey, CHANGE_WINDOW);
        rateLimiter.recordAttempt(ipKey, CHANGE_WINDOW);

        auditService.record(userId, AuditAction.EMAIL_CHANGED, "User", String.valueOf(userId),
                previous, user.getEmail());
        return UserResponse.from(user);
    }

    /** Un agent corrige l'adresse d'un compte depuis le back-office. */
    @Transactional
    public UserResponse changeByAdmin(Long adminId, boolean adminIsSuperAdmin, Long userId, String rawEmail) {
        User user = load(userId);
        boolean staff = user.getRoles().stream().anyMatch(role -> role.getName().isStaff());
        if (staff && !adminIsSuperAdmin) {
            // Changer l'email d'un administrateur, puis demander un nouveau mot de passe,
            // reviendrait a prendre son compte.
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "Seul un super-administrateur peut modifier l'email d'un membre du staff.");
        }

        String previous = user.getEmail();
        boolean wasVerified = user.isEmailVerified();
        String firstName = user.getFirstName();
        apply(user, rawEmail);

        if (wasVerified) {
            // L'ancienne adresse etait bien la sienne : on la previent du changement.
            transactionalMail.sendAfterCommit(previous, "Votre adresse email a ete modifiee - RAF",
                    MailTemplates.emailChanged(firstName, mask(user.getEmail())));
        }
        auditService.record(adminId, AuditAction.EMAIL_CHANGED_BY_ADMIN, "User", String.valueOf(userId),
                previous, user.getEmail());
        return UserResponse.from(user);
    }

    private void apply(User user, String rawEmail) {
        String email = rawEmail.trim().toLowerCase();
        if (email.equalsIgnoreCase(user.getEmail())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "C'est deja l'adresse de ce compte.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_USED);
        }
        user.setEmail(email);
        user.setEmailVerifiedAt(null);
        // Un lien de mot de passe oublie parti vers l'ancienne adresse ne doit plus servir.
        verificationTokenRepository.consumePending(user.getId(), VerificationTokenType.PASSWORD_RESET, Instant.now());
        // Invalide aussi le lien de verification envoye a l'ancienne adresse.
        verificationService.sendEmailVerification(user);
    }

    private User load(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETED);
        }
        return user;
    }

    /** « j***@gmail.com » : l'ancienne adresse apprend le changement sans tout lire. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
