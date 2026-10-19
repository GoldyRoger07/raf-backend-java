package com.raikiservice.raf.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import com.raikiservice.raf.auth.domain.OtpCode;
import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.OtpCodeRepository;
import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.util.Hashes;
import com.raikiservice.raf.notification.sms.SmsSender;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Codes a usage unique envoyes par SMS.
 *
 * <p>Trois garde-fous, car 6 chiffres se devinent vite : duree de vie courte,
 * plafond d'essais par code, et delai minimum entre deux envois.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpCodeRepository otpCodeRepository;
    private final OtpAttemptRecorder attemptRecorder;
    private final PasswordEncoder passwordEncoder;
    private final SmsSender smsSender;
    private final AppProperties appProperties;

    @Transactional
    public void send(User user, OtpPurpose purpose, String destination) {
        send(user, purpose, destination, code -> "RAF : votre code de verification est " + code + ".");
    }

    /**
     * Variante au message personnalise : le joueur doit savoir ce qu'il autorise
     * (par exemple le montant d'un retrait au guichet) avant de donner son code.
     * La duree de validite est ajoutee a la fin du message.
     */
    @Transactional
    public void send(User user, OtpPurpose purpose, String destination,
            Function<String, String> messageForCode) {
        assertNoCooldown(user.getId(), purpose);

        // Un nouveau code annule le precedent, sinon deux codes resteraient valables.
        otpCodeRepository.consumePending(user.getId(), purpose, Instant.now());

        String code = Hashes.numericCode(appProperties.otp().length());

        OtpCode entity = new OtpCode();
        entity.setUser(user);
        entity.setPurpose(purpose);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setDestination(destination);
        entity.setExpiresAt(Instant.now().plusSeconds(appProperties.otp().ttl().getSeconds()));
        otpCodeRepository.save(entity);

        smsSender.send(destination, messageForCode.apply(code)
                + " Il expire dans " + appProperties.otp().ttl().toMinutes() + " minutes.");
    }

    /**
     * Verifie un code. Chaque tentative est comptee, y compris les echecs, afin que
     * le plafond d'essais ne puisse pas etre contourne.
     */
    @Transactional
    public void verify(User user, OtpPurpose purpose, String code) {
        OtpCode entity = otpCodeRepository
                .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose)
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_INVALID));

        if (entity.getConsumedAt() != null) {
            throw new ApiException(ErrorCode.OTP_INVALID);
        }
        if (entity.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.OTP_EXPIRED);
        }
        if (entity.getAttempts() >= appProperties.otp().maxAttempts()) {
            throw new ApiException(ErrorCode.OTP_TOO_MANY_ATTEMPTS);
        }

        // Comptabilise avant de comparer, et hors de cette transaction : un echec
        // annulerait l'increment et rendrait le plafond d'essais inoperant.
        attemptRecorder.increment(entity.getId());

        if (!passwordEncoder.matches(code, entity.getCodeHash())) {
            throw new ApiException(ErrorCode.OTP_INVALID);
        }

        // Deux requetes simultanees portant le meme code passent toutes deux les
        // controles ci-dessus : seule la premiere a consommer le code l'emporte.
        if (otpCodeRepository.consume(entity.getId(), Instant.now()) == 0) {
            throw new ApiException(ErrorCode.OTP_INVALID);
        }
    }

    private void assertNoCooldown(Long userId, OtpPurpose purpose) {
        Duration cooldown = appProperties.otp().resendCooldown();
        Optional<OtpCode> last = otpCodeRepository
                .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose);

        if (last.isPresent() && last.get().getCreatedAt().plusSeconds(cooldown.getSeconds())
                .isAfter(Instant.now())) {
            throw new ApiException(ErrorCode.OTP_COOLDOWN,
                    "Patientez " + cooldown.getSeconds() + " secondes avant de demander un nouveau code.");
        }
    }
}
