package com.raikiservice.raf.auth.service;

import com.raikiservice.raf.auth.repository.OtpCodeRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incremente le compteur d'essais d'un code OTP dans une transaction independante.
 *
 * <p>Un code errone leve une exception, ce qui annule la transaction courante. Sans
 * cette transaction separee, l'increment serait annule avec elle et le plafond
 * d'essais ne bloquerait jamais une attaque par force brute.
 */
@Service
@RequiredArgsConstructor
public class OtpAttemptRecorder {

    private final OtpCodeRepository otpCodeRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(Long otpCodeId) {
        otpCodeRepository.incrementAttempts(otpCodeId);
    }
}
