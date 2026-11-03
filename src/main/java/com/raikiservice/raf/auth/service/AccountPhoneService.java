package com.raikiservice.raf.auth.service;

import java.time.Duration;
import java.time.Instant;

import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.OtpCodeRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.common.audit.AuditAction;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.ratelimit.RateLimiter;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correction du numero de telephone par le joueur, tant qu'il n'est pas verifie.
 *
 * <p>Un numero verifie ne se change pas depuis l'app : c'est lui qui recoit les
 * codes de retrait, le changer reviendrait a detourner les gains. Le support s'en
 * charge.
 */
@Service
@RequiredArgsConstructor
public class AccountPhoneService {

    private static final int MAX_CHANGES_PER_ACCOUNT = 3;
    private static final int MAX_CHANGES_PER_IP = 20;
    private static final Duration CHANGE_WINDOW = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpCodeRepository otpCodeRepository;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;

    @Transactional
    public UserResponse correctOwnPhone(Long userId, String password, String rawPhone, String clientIp) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETED);
        }
        if (user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_VERIFIED,
                    "Votre numero est deja verifie : contactez le support pour le changer.");
        }
        if (password == null || password.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Le mot de passe est obligatoire.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }

        String phone = rawPhone.trim();
        if (phone.equals(user.getPhone())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "C'est deja le numero de ce compte.");
        }
        // Sans plafond, on pourrait sonder quels numeros ont deja un compte.
        String accountKey = "phone-change:user:" + userId;
        String ipKey = "phone-change:ip:" + clientIp;
        rateLimiter.assertNotBlocked(accountKey, MAX_CHANGES_PER_ACCOUNT);
        rateLimiter.assertNotBlocked(ipKey, MAX_CHANGES_PER_IP);
        rateLimiter.recordAttempt(accountKey, CHANGE_WINDOW);
        rateLimiter.recordAttempt(ipKey, CHANGE_WINDOW);
        if (userRepository.existsByPhone(phone)) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_USED);
        }

        String previous = user.getPhone();
        user.setPhone(phone);
        // Un code deja envoye a l'ancien numero ne doit plus valider le nouveau.
        otpCodeRepository.consumePending(userId, OtpPurpose.PHONE_VERIFY, Instant.now());

        auditService.record(userId, AuditAction.PHONE_CHANGED, "User", String.valueOf(userId),
                previous, phone);
        return UserResponse.from(user);
    }
}
