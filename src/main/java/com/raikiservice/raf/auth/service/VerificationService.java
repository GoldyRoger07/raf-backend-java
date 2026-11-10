package com.raikiservice.raf.auth.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.VerificationToken;
import com.raikiservice.raf.auth.domain.VerificationTokenType;
import com.raikiservice.raf.auth.repository.VerificationTokenRepository;
import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.util.Hashes;
import com.raikiservice.raf.notification.mail.TransactionalMail;
import com.raikiservice.raf.notification.mail.MailTemplates;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jetons a usage unique : verification d'email et reinitialisation de mot de passe.
 *
 * <p>L'email de verification porte deux moyens au choix : le lien, et un code a
 * saisir dans l'app (meme duree de vie). Le premier utilise annule l'autre.
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final VerificationTokenRepository verificationTokenRepository;
    private final TransactionalMail transactionalMail;
    private final AppProperties appProperties;
    private final OtpService otpService;

    @Transactional
    public void sendEmailVerification(User user) {
        if (user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }

        Duration ttl = appProperties.verification().emailTokenTtl();
        String rawToken = issue(user, VerificationTokenType.EMAIL_VERIFY, ttl.getSeconds());
        String code = otpService.issue(user, OtpPurpose.EMAIL_VERIFY, user.getEmail(), ttl);

        String link = appProperties.frontendUrl() + "/verification-email?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        transactionalMail.sendAfterCommit(user.getEmail(), "Confirmez votre adresse email - RAF",
                MailTemplates.verifyEmail(user.getFirstName(), link, code));
    }

    /** L'email vient d'etre confirme : ni le lien ni le code encore en attente ne servent plus. */
    @Transactional
    public void cancelEmailVerification(User user) {
        verificationTokenRepository.consumePending(user.getId(), VerificationTokenType.EMAIL_VERIFY, Instant.now());
        otpService.cancelPending(user, OtpPurpose.EMAIL_VERIFY);
    }

    @Transactional
    public void sendPasswordReset(User user) {
        String rawToken = issue(user, VerificationTokenType.PASSWORD_RESET,
                appProperties.verification().passwordResetTtl().getSeconds());

        String link = appProperties.frontendUrl() + "/nouveau-mot-de-passe?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        transactionalMail.sendAfterCommit(user.getEmail(), "Reinitialisation de votre mot de passe - RAF",
                MailTemplates.resetPassword(user.getFirstName(), link));
    }

    /** Valide et consomme un jeton. Un jeton ne peut servir qu'une seule fois. */
    @Transactional
    public User consume(String rawToken, VerificationTokenType type) {
        VerificationToken token = verificationTokenRepository
                .findByTokenHashAndType(Hashes.sha256Hex(rawToken), type)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));

        // Email deja confirme (par le code de l'app, ou un premier clic) : le lien
        // n'a plus d'objet, mais ce n'est pas une erreur pour le joueur.
        if (type == VerificationTokenType.EMAIL_VERIFY && token.getUser().isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        if (token.getConsumedAt() != null) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }

        token.setConsumedAt(Instant.now());
        return token.getUser();
    }

    private String issue(User user, VerificationTokenType type, long ttlSeconds) {
        // Emettre un nouveau jeton invalide les precedents : un seul lien actif a la fois.
        verificationTokenRepository.consumePending(user.getId(), type, Instant.now());

        String rawToken = Hashes.randomToken();

        VerificationToken token = new VerificationToken();
        token.setUser(user);
        token.setType(type);
        token.setTokenHash(Hashes.sha256Hex(rawToken));
        token.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        verificationTokenRepository.save(token);

        return rawToken;
    }
}
