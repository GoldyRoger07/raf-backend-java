package com.raikiservice.raf.auth;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.auth.service.AccountEmailService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Correction d'une adresse email mal saisie, par le joueur ou par un agent. */
class AccountEmailTest extends IntegrationTest {

    private static final String PASSWORD = "MotDePasse123";

    @Autowired private AccountEmailService accountEmailService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mvc;

    @Test
    void aPlayerCorrectsAnUnverifiedAddress() throws Exception {
        User player = unverifiedPlayer();
        String typo = player.getEmail();
        String fixed = "corrige-" + typo;

        accountEmailService.correctOwnEmail(player.getId(), PASSWORD, "  " + fixed.toUpperCase() + " ", "1.2.3.4");

        User updated = userRepository.findById(player.getId()).orElseThrow();
        assertThat(updated.getEmail()).isEqualTo(fixed);
        assertThat(updated.isEmailVerified()).isFalse();
        assertThat(userRepository.existsByEmailIgnoreCase(typo)).isFalse();
        eventually(() -> assertThat(mails.to(fixed)).anyMatch(mail -> mail.subject().contains("Confirmez")));
    }

    @Test
    void aVerifiedAddressCannotBeChangedFromTheApp() {
        User player = data.player();   // email deja verifie
        player.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(player);

        assertApiError(ErrorCode.EMAIL_ALREADY_VERIFIED, () ->
                accountEmailService.correctOwnEmail(player.getId(), PASSWORD, "autre-" + player.getEmail(), "1.2.3.4"));
    }

    @Test
    void theAppAsksForThePasswordAndAFreeAddress() {
        User player = unverifiedPlayer();
        User other = data.player();

        assertApiError(ErrorCode.CURRENT_PASSWORD_INVALID, () ->
                accountEmailService.correctOwnEmail(player.getId(), "faux", "x-" + player.getEmail(), "1.2.3.4"));
        assertApiError(ErrorCode.EMAIL_ALREADY_USED, () ->
                accountEmailService.correctOwnEmail(player.getId(), PASSWORD, other.getEmail(), "1.2.3.4"));
    }

    @Test
    void anAgentChangesAVerifiedAddressAndTheOldOneIsWarned() throws Exception {
        User player = data.player();
        String old = player.getEmail();
        String fresh = "nouvelle-" + old;

        accountEmailService.changeByAdmin(null, false, player.getId(), fresh);

        User updated = userRepository.findById(player.getId()).orElseThrow();
        assertThat(updated.getEmail()).isEqualTo(fresh);
        assertThat(updated.isEmailVerified()).isFalse();
        eventually(() -> {
            assertThat(mails.to(old)).anyMatch(mail -> mail.subject().contains("modifiee"));
            assertThat(mails.to(fresh)).anyMatch(mail -> mail.subject().contains("Confirmez"));
        });
    }

    @Test
    void onlyASuperAdminChangesAStaffAddress() {
        User moderator = data.moderator();

        assertApiError(ErrorCode.ACCESS_DENIED, () ->
                accountEmailService.changeByAdmin(null, false, moderator.getId(), "x-" + moderator.getEmail()));
        accountEmailService.changeByAdmin(null, true, moderator.getId(), "x-" + moderator.getEmail());
    }

    @Test
    void anAdminReachesThePlayerActions() throws Exception {
        User player = data.player();
        String admin = "Bearer " + jwtService.generateAccessToken(
                userRepository.findById(data.admin().getId()).orElseThrow());

        mvc.perform(post("/api/admin/players/" + player.getId() + "/test").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"test\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/players/" + player.getId() + "/email").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"http-" + player.getEmail() + "\"}"))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(player.getId()).orElseThrow().isTest()).isTrue();
    }

    private User unverifiedPlayer() {
        User player = data.player();
        player.setEmailVerifiedAt(null);
        player.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(player);
    }
}
