package com.raikiservice.raf.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.Role;
import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.domain.VerificationTokenType;
import com.raikiservice.raf.auth.repository.RoleRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.auth.web.dto.ChangePasswordRequest;
import com.raikiservice.raf.auth.web.dto.LoginRequest;
import com.raikiservice.raf.auth.web.dto.RegisterRequest;
import com.raikiservice.raf.auth.web.dto.ResetPasswordRequest;
import com.raikiservice.raf.auth.web.dto.TokenResponse;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.common.audit.AuditAction;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.ratelimit.RateLimiter;
import com.raikiservice.raf.common.util.RequestContext;
import com.raikiservice.raf.notification.mail.MailSender;
import com.raikiservice.raf.notification.mail.MailTemplates;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_LOGIN_ATTEMPTS_PER_ACCOUNT = 8;
    private static final int MAX_LOGIN_ATTEMPTS_PER_IP = 30;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final VerificationService verificationService;
    private final OtpService otpService;
    private final MailSender mailSender;
    private final AuditService auditService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String phone = request.phone().trim();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_USED);
        }
        if (userRepository.existsByPhone(phone)) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_USED);
        }

        Role playerRole = roleRepository.findByName(RoleName.PLAYER)
                .orElseThrow(() -> new IllegalStateException(
                        "Le role PLAYER est absent : la migration V1 n'a pas ete appliquee."));

        User user = new User();
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(playerRole));

        User saved = userRepository.save(user);
        verificationService.sendEmailVerification(saved);
        auditService.record(saved.getId(), AuditAction.USER_REGISTERED, "User",
                String.valueOf(saved.getId()));

        return UserResponse.from(saved);
    }

    /**
     * La connexion reste possible sans email verifie : le blocage porte sur les
     * operations sensibles (achat de ticket, retrait), pas sur l'acces au compte.
     */
    @Transactional
    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String email = request.email().trim().toLowerCase();

        // Plafonne par compte ET par origine : le premier protege un joueur cible,
        // le second freine un robot qui balaie de nombreux comptes.
        String accountKey = "login:account:" + email;
        String ipKey = "login:ip:" + RequestContext.clientIp(httpRequest);
        rateLimiter.assertNotBlocked(accountKey, MAX_LOGIN_ATTEMPTS_PER_ACCOUNT);
        rateLimiter.assertNotBlocked(ipKey, MAX_LOGIN_ATTEMPTS_PER_IP);

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    // Meme erreur que pour un mot de passe faux : on ne revele pas
                    // quels emails existent en base.
                    recordFailedLogin(accountKey, ipKey);
                    auditService.record(null, AuditAction.USER_LOGIN_FAILED, "User", email);
                    return new ApiException(ErrorCode.INVALID_CREDENTIALS);
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            recordFailedLogin(accountKey, ipKey);
            auditService.record(user.getId(), AuditAction.USER_LOGIN_FAILED, "User",
                    String.valueOf(user.getId()));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        assertAccountUsable(user);
        rateLimiter.reset(accountKey);

        user.setLastLoginAt(Instant.now());
        auditService.record(user.getId(), AuditAction.USER_LOGGED_IN, "User",
                String.valueOf(user.getId()));

        return issueTokens(user, httpRequest);
    }

    @Transactional
    public TokenResponse refresh(String refreshToken, HttpServletRequest httpRequest) {
        RefreshTokenService.Issued issued = refreshTokenService.rotate(refreshToken, httpRequest);
        User user = issued.entity().getUser();

        return TokenResponse.of(
                jwtService.generateAccessToken(user),
                issued.rawToken(),
                jwtService.accessTokenTtlSeconds(),
                UserResponse.from(user));
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = verificationService.consume(token, VerificationTokenType.EMAIL_VERIFY);

        if (user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }

        user.setEmailVerifiedAt(Instant.now());
        auditService.record(user.getId(), AuditAction.EMAIL_VERIFIED, "User",
                String.valueOf(user.getId()));
    }

    /**
     * Le renvoi de lien repond toujours la meme chose, que l'email existe ou non :
     * l'endpoint est public et ne doit pas servir a enumerer les comptes.
     */
    @Transactional
    public void resendEmailVerification(String email) {
        userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .filter(user -> !user.isEmailVerified())
                .ifPresent(verificationService::sendEmailVerification);
    }

    @Transactional
    public void sendPhoneOtp(Long userId) {
        User user = loadUser(userId);

        if (user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_VERIFIED);
        }

        otpService.send(user, OtpPurpose.PHONE_VERIFY, user.getPhone());
    }

    @Transactional
    public void verifyPhone(Long userId, String code) {
        User user = loadUser(userId);

        if (user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_VERIFIED);
        }

        otpService.verify(user, OtpPurpose.PHONE_VERIFY, code);
        user.setPhoneVerifiedAt(Instant.now());
        auditService.record(userId, AuditAction.PHONE_VERIFIED, "User", String.valueOf(userId));
    }

    /** Reponse identique dans tous les cas, pour la meme raison que le renvoi de lien. */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .ifPresent(user -> {
                    verificationService.sendPasswordReset(user);
                    auditService.record(user.getId(), AuditAction.PASSWORD_RESET_REQUESTED, "User",
                            String.valueOf(user.getId()));
                });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = verificationService.consume(request.token(), VerificationTokenType.PASSWORD_RESET);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenService.revokeAllAfterPasswordChange(user.getId());

        auditService.record(user.getId(), AuditAction.PASSWORD_RESET_COMPLETED, "User",
                String.valueOf(user.getId()));
        mailSender.send(user.getEmail(), "Votre mot de passe a ete modifie - RAF",
                MailTemplates.passwordChanged(user.getFirstName()));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = loadUser(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INVALID);
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Un changement de mot de passe ferme toutes les sessions : si le compte etait
        // compromis, l'intrus perd son acces.
        refreshTokenService.revokeAllAfterPasswordChange(userId);

        auditService.record(userId, AuditAction.PASSWORD_CHANGED, "User", String.valueOf(userId));
        mailSender.send(user.getEmail(), "Votre mot de passe a ete modifie - RAF",
                MailTemplates.passwordChanged(user.getFirstName()));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        return UserResponse.from(loadUser(userId));
    }

    private TokenResponse issueTokens(User user, HttpServletRequest httpRequest) {
        RefreshTokenService.Issued issued = refreshTokenService.issue(user, httpRequest);

        return TokenResponse.of(
                jwtService.generateAccessToken(user),
                issued.rawToken(),
                jwtService.accessTokenTtlSeconds(),
                UserResponse.from(user));
    }

    private void recordFailedLogin(String accountKey, String ipKey) {
        rateLimiter.recordAttempt(accountKey, LOGIN_WINDOW);
        rateLimiter.recordAttempt(ipKey, LOGIN_WINDOW);
    }

    private void assertAccountUsable(User user) {
        switch (user.getStatus()) {
            case BANNED -> throw new ApiException(ErrorCode.ACCOUNT_BANNED);
            case SUSPENDED -> throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED);
            default -> {
            }
        }
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
    }
}
