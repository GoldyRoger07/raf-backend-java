package com.raikiservice.raf.admin;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rappel de confirmation de l'email : actif par defaut, coupe ou reactive par un
 * administrateur, lu sans connexion par l'app joueur.
 */
class PlayerAppConfigTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    /** Le reglage est global : on le remet a sa valeur par defaut pour les autres tests. */
    @AfterEach
    void restore() throws Exception {
        update(data.admin(), true);
        updateJson(data.admin(), "{\"emailReminderDelaySeconds\":10}");
    }

    @Test
    void theReminderDelayIsTenSecondsByDefaultAndAnAdminCanChangeIt() throws Exception {
        mvc.perform(get("/api/public/app-config"))
                .andExpect(jsonPath("$.emailReminderDelaySeconds").value(10));

        updateJson(data.admin(), "{\"emailReminderDelaySeconds\":45}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailReminderDelaySeconds").value(45))
                .andExpect(jsonPath("$.emailReminderEnabled").value(true));
        mvc.perform(get("/api/public/app-config"))
                .andExpect(jsonPath("$.emailReminderDelaySeconds").value(45));

        // Un interrupteur modifie seul garde le delai.
        update(data.admin(), false).andExpect(jsonPath("$.emailReminderDelaySeconds").value(45));
    }

    @Test
    void theReminderDelayMustStayWithinBounds() throws Exception {
        updateJson(data.admin(), "{\"emailReminderDelaySeconds\":4}").andExpect(status().isBadRequest());
        updateJson(data.admin(), "{\"emailReminderDelaySeconds\":86401}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/app-config"))
                .andExpect(jsonPath("$.emailReminderDelaySeconds").value(10));
    }

    @Test
    void theReminderIsOnByDefaultAndAnAdminCanTurnItOff() throws Exception {
        mvc.perform(get("/api/public/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailReminderEnabled").value(true));

        update(data.admin(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailReminderEnabled").value(false));
        mvc.perform(get("/api/public/app-config"))
                .andExpect(jsonPath("$.emailReminderEnabled").value(false));

        update(data.admin(), true).andExpect(jsonPath("$.emailReminderEnabled").value(true));
        mvc.perform(get("/api/public/app-config"))
                .andExpect(jsonPath("$.emailReminderEnabled").value(true));
    }

    @Test
    void onlyAdministratorsChangeIt() throws Exception {
        update(data.moderator(), false).andExpect(status().isForbidden());
        update(data.player(), false).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/player-app").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailReminderEnabled\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void itStaysOutOfTheNumericSettings() throws Exception {
        update(data.admin(), false);
        mvc.perform(get("/api/admin/settings").header("Authorization", token(data.superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", not(hasItem("app.email_reminder.enabled"))));
    }

    private ResultActions update(User user, boolean enabled) throws Exception {
        return updateJson(user, "{\"emailReminderEnabled\":" + enabled + "}");
    }

    private ResultActions updateJson(User user, String body) throws Exception {
        return mvc.perform(put("/api/admin/player-app")
                .header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
