package com.raikiservice.raf.admin;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.audit.AuditLogRepository;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingResponse;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Parametres applicatifs : lecture et modification par le seul super-administrateur. */
class SettingsAdminTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private SettingsService settingsService;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    void onlyTheSuperAdminReachesTheSettings() throws Exception {
        mvc.perform(get("/api/admin/settings").header("Authorization", token(data.admin())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/settings/" + SettingKeys.CREDIT_HTG_RATE)
                        .header("Authorization", token(data.admin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"6\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/settings").header("Authorization", token(data.superAdmin())))
                .andExpect(status().isOk());
        assertThat(settingsService.list()).extracting(SettingResponse::key)
                .contains(SettingKeys.CREDIT_HTG_RATE, SettingKeys.GAME_QUESTION_GRACE_MS);
    }

    @Test
    void anUpdateIsValidatedAndAudited() throws Exception {
        User superAdmin = data.superAdmin();
        String key = SettingKeys.QUESTION_IMPORT_MAX_ROWS;
        long original = settingsService.getLong(key, 0);
        try {
            SettingResponse updated = settingsService.updateByAdmin(superAdmin.getId(), key, " 1234 ");
            assertThat(updated.value()).isEqualTo("1234");
            assertThat(settingsService.getLong(key, 0)).isEqualTo(1234);
            eventually(() -> assertThat(auditLogRepository.findAll())
                    .anyMatch(entry -> "SETTING_UPDATED".equals(entry.getAction())
                            && key.equals(entry.getEntityId())
                            && "1234".equals(entry.getAfterState())));

            assertApiError(ErrorCode.SETTING_INVALID,
                    () -> settingsService.updateByAdmin(superAdmin.getId(), key, "beaucoup"));
            assertApiError(ErrorCode.SETTING_INVALID,
                    () -> settingsService.updateByAdmin(superAdmin.getId(), key, "0"));
            assertApiError(ErrorCode.SETTING_INVALID,
                    () -> settingsService.updateByAdmin(superAdmin.getId(), SettingKeys.CREDIT_HTG_RATE, "0"));
            assertApiError(ErrorCode.RESOURCE_NOT_FOUND,
                    () -> settingsService.updateByAdmin(superAdmin.getId(), "inconnu.cle", "1"));
        } finally {
            settingsService.updateByAdmin(superAdmin.getId(), key, String.valueOf(original));
        }
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
