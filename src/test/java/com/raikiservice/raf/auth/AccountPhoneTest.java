package com.raikiservice.raf.auth;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.AccountPhoneService;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Telephone : verifie apres l'email, corrigeable tant qu'il n'est pas verifie. */
class AccountPhoneTest extends IntegrationTest {

    private static final String PASSWORD = "MotDePasse123";

    @Autowired private AccountPhoneService accountPhoneService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void thePhoneIsVerifiedOnlyAfterTheEmail() {
        User player = data.player(false);
        player.setEmailVerifiedAt(null);
        userRepository.save(player);

        assertApiError(ErrorCode.EMAIL_NOT_VERIFIED, () -> authService.sendPhoneOtp(player.getId()));
        assertApiError(ErrorCode.EMAIL_NOT_VERIFIED, () -> authService.verifyPhone(player.getId(), "123456"));
        assertThat(sms.lastMessage(player.getPhone())).isNull();
    }

    @Test
    void aWrongNumberIsCorrectedAndTheOldCodeNoLongerWorks() {
        User player = playerWithUnverifiedPhone();
        authService.sendPhoneOtp(player.getId());
        String oldCode = sms.lastCode(player.getPhone());
        String fixed = freeNumber();

        accountPhoneService.correctOwnPhone(player.getId(), PASSWORD, fixed, "1.2.3.4");

        assertThat(userRepository.findById(player.getId()).orElseThrow().getPhone()).isEqualTo(fixed);
        assertApiError(ErrorCode.OTP_INVALID, () -> authService.verifyPhone(player.getId(), oldCode));

        authService.sendPhoneOtp(player.getId());
        authService.verifyPhone(player.getId(), sms.lastCode(fixed));
        assertThat(userRepository.findById(player.getId()).orElseThrow().isPhoneVerified()).isTrue();
    }

    @Test
    void aVerifiedOrTakenNumberIsRefused() {
        User verified = data.player(true);
        verified.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(verified);
        assertApiError(ErrorCode.PHONE_ALREADY_VERIFIED, () ->
                accountPhoneService.correctOwnPhone(verified.getId(), PASSWORD, freeNumber(), "1.2.3.4"));

        User player = playerWithUnverifiedPhone();
        assertApiError(ErrorCode.PHONE_ALREADY_USED, () ->
                accountPhoneService.correctOwnPhone(player.getId(), PASSWORD, verified.getPhone(), "1.2.3.4"));
        assertApiError(ErrorCode.CURRENT_PASSWORD_INVALID, () ->
                accountPhoneService.correctOwnPhone(player.getId(), "faux", freeNumber(), "1.2.3.4"));
    }

    private User playerWithUnverifiedPhone() {
        User player = data.player(false);
        player.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(player);
    }

    /** Numero libre : un autre joueur de test, cree puis renomme pour liberer le sien. */
    private String freeNumber() {
        User other = data.player();
        String phone = other.getPhone();
        other.setPhone("libre-" + other.getId());
        userRepository.save(other);
        return phone;
    }
}
