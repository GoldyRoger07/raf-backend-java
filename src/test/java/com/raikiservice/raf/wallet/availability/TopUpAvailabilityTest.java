package com.raikiservice.raf.wallet.availability;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Interrupteur « Disponible pour les recharges » : un administrateur se declare
 * en ligne, les joueurs le voient sans connexion, et il repasse hors ligne.
 */
class TopUpAvailabilityTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void anAgentGoesOnlineThenOfflineAndPlayersSeeIt() throws Exception {
        User admin = data.admin();
        try {
            mvc.perform(post("/api/admin/topup-availability/heartbeat").header("Authorization", token(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.online").value(true))
                    .andExpect(jsonPath("$.available").value(true));
            mvc.perform(get("/api/public/topup-availability"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.lastSeenAt").doesNotExist());

            mvc.perform(delete("/api/admin/topup-availability/heartbeat").header("Authorization", token(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.online").value(false))
                    .andExpect(jsonPath("$.available").value(false));
            mvc.perform(get("/api/public/topup-availability"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.lastSeenAt").exists());
        } finally {
            // L'etat est en memoire et partage par le contexte de test.
            mvc.perform(delete("/api/admin/topup-availability/heartbeat").header("Authorization", token(admin)));
        }
    }

    @Test
    void onlyAdministratorsCanDeclareThemselvesAvailable() throws Exception {
        mvc.perform(post("/api/admin/topup-availability/heartbeat").header("Authorization", token(data.moderator())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/topup-availability/heartbeat").header("Authorization", token(data.player())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/topup-availability/heartbeat"))
                .andExpect(status().isUnauthorized());
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
