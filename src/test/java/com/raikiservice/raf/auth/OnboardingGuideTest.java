package com.raikiservice.raf.auth;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.auth.web.dto.RegisterRequest;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Guide de demarrage de l'app : propose une fois aux nouveaux comptes, memorise sur le compte. */
class OnboardingGuideTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;

    @Test
    void aNewAccountIsOfferedTheGuideOnceOnAnyDevice() throws Exception {
        String email = "guide-" + UUID.randomUUID() + "@raf.test";
        String phone = "+1" + (2_000_000_000L + ThreadLocalRandom.current().nextLong(7_000_000_000L));
        authService.register(new RegisterRequest(email, phone, "MotDePasse123", "Nouveau", "Joueur", null));
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        mvc.perform(get("/api/me").header("Authorization", token(user)))
                .andExpect(jsonPath("$.onboardingPending").value(true));

        mvc.perform(post("/api/me/onboarding/done").header("Authorization", token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingPending").value(false));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isOnboardingPending()).isFalse();
    }

    @Test
    void existingAccountsAreNotOfferedTheGuide() throws Exception {
        User existing = data.player();
        mvc.perform(get("/api/me").header("Authorization", token(existing)))
                .andExpect(jsonPath("$.onboardingPending").value(false));
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
