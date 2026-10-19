package com.raikiservice.raf.auth;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qui accede a quoi, verifie au niveau HTTP : les regles d'URL de la
 * configuration de securite s'appliquent avant les @PreAuthorize des controleurs.
 */
class StaffAccessTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void aModeratorManagesTheQuestionBankButNotMoney() throws Exception {
        String moderator = token(data.moderator());

        mvc.perform(get("/api/admin/questions").header("Authorization", moderator))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/questions/pool-status").param("categoryId", "1")
                        .param("questionsPerGame", "3").header("Authorization", moderator))
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));
        mvc.perform(get("/api/categories/all").header("Authorization", moderator))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/quizzes").header("Authorization", moderator))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/wallet/withdrawals").header("Authorization", moderator))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/dashboard").header("Authorization", moderator))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlayerReachesNoAdministration() throws Exception {
        String player = token(data.player());

        mvc.perform(get("/api/admin/questions").header("Authorization", player))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/quizzes").header("Authorization", player))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminReachesBoth() throws Exception {
        String admin = token(data.admin());

        mvc.perform(get("/api/admin/questions").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/quizzes").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
