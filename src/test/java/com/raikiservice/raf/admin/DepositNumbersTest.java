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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Numeros MonCash / NatCash des recharges a distance : l'administrateur les
 * renseigne et les active, l'app les lit sans connexion.
 */
class DepositNumbersTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void anAdminSetsTheNumbersAndPlayersSeeOnlyTheActiveOnes() throws Exception {
        User admin = data.admin();

        update(admin, true, "3712-3456", false, "+509 4123 4567")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moncashEnabled").value(true))
                .andExpect(jsonPath("$.moncashNumber").value("+50937123456"))
                .andExpect(jsonPath("$.natcashEnabled").value(false))
                .andExpect(jsonPath("$.natcashNumber").value("+50941234567"));

        mvc.perform(get("/api/public/deposit-numbers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moncashNumber").value("+50937123456"))
                .andExpect(jsonPath("$.natcashNumber").doesNotExist());

        update(admin, false, "", false, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moncashNumber").doesNotExist());
        mvc.perform(get("/api/public/deposit-numbers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moncashNumber").doesNotExist());
    }

    @Test
    void onlyHaitianNumbersAreAccepted() throws Exception {
        User admin = data.admin();

        update(admin, true, "+33 6 12 34 56 78", false, "").andExpect(status().isBadRequest());
        update(admin, true, "1234", false, "").andExpect(status().isBadRequest());
        update(admin, true, "", false, "").andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdministratorsChangeTheNumbers() throws Exception {
        update(data.moderator(), true, "37123456", false, "").andExpect(status().isForbidden());
        update(data.player(), true, "37123456", false, "").andExpect(status().isForbidden());
    }

    @Test
    void theNumbersStayOutOfTheNumericSettings() throws Exception {
        update(data.admin(), false, "37123456", false, "").andExpect(status().isOk());

        mvc.perform(get("/api/admin/settings").header("Authorization", token(data.superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", not(hasItem("deposit.moncash.number"))));
    }

    private ResultActions update(User user, boolean moncashEnabled, String moncash,
            boolean natcashEnabled, String natcash) throws Exception {
        return mvc.perform(put("/api/admin/deposit-numbers")
                .header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"moncashEnabled\":" + moncashEnabled + ",\"moncashNumber\":\"" + moncash
                        + "\",\"natcashEnabled\":" + natcashEnabled + ",\"natcashNumber\":\"" + natcash + "\"}"));
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
