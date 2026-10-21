package com.raikiservice.raf.admin;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lien Instagram : l'administrateur l'active ou le desactive, l'app et le site le
 * lisent sans connexion, et seule une adresse instagram.com est acceptee.
 */
class SocialLinksTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void anAdminTurnsTheLinkOnAndOffAndThePublicSeesIt() throws Exception {
        User admin = data.admin();

        update(admin, true, "@raiki.services")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instagramEnabled").value(true))
                .andExpect(jsonPath("$.instagramUrl").value("https://www.instagram.com/raiki.services/"))
                .andExpect(jsonPath("$.instagramHandle").value("@raiki.services"));
        mvc.perform(get("/api/public/social"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instagramUrl").value("https://www.instagram.com/raiki.services/"));

        // Desactive : le compte reste enregistre, mais n'est plus montre.
        update(admin, false, "https://www.instagram.com/raiki.services/")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instagramEnabled").value(false))
                .andExpect(jsonPath("$.instagramUrl").value("https://www.instagram.com/raiki.services/"));
        mvc.perform(get("/api/public/social"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instagramUrl").doesNotExist());
    }

    @Test
    void onlyAnInstagramAccountIsAccepted() throws Exception {
        User admin = data.admin();

        update(admin, true, "https://evil.example/raiki").andExpect(status().isBadRequest());
        update(admin, true, "").andExpect(status().isBadRequest());
        update(admin, true, "instagram.com/raikiservices?igsh=abc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instagramUrl").value("https://www.instagram.com/raikiservices/"));
    }

    @Test
    void onlyAdministratorsChangeTheLink() throws Exception {
        update(data.moderator(), true, "@raikiservices").andExpect(status().isForbidden());
        update(data.player(), true, "@raikiservices").andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/social").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLinkStaysOutOfTheNumericSettings() throws Exception {
        User superAdmin = data.superAdmin();

        mvc.perform(get("/api/admin/settings").header("Authorization", token(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", not(hasItem("social.instagram.enabled"))));
        mvc.perform(put("/api/admin/settings/social.instagram.enabled")
                        .header("Authorization", token(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1\"}"))
                .andExpect(status().isNotFound());
    }

    private ResultActions update(User user, boolean enabled, String account) throws Exception {
        return mvc.perform(put("/api/admin/social")
                .header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instagramEnabled\":" + enabled + ",\"instagramAccount\":\"" + account + "\"}"));
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
