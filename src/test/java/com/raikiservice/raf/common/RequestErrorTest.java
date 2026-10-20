package com.raikiservice.raf.common;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Une requete mal formee par le client est une erreur 4xx, jamais une erreur interne. */
class RequestErrorTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void aMissingParameterIsABadRequest() throws Exception {
        mvc.perform(get("/api/game/sessions").header("Authorization", token(data.player())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quizId"));
    }

    @Test
    void anUnreadableParameterIsABadRequest() throws Exception {
        mvc.perform(get("/api/game/sessions").param("quizId", "abc")
                        .header("Authorization", token(data.player())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quizId"));
    }

    @Test
    void anUnknownAddressIsNotFound() throws Exception {
        mvc.perform(get("/api/nexiste-pas").header("Authorization", token(data.player())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anUnsupportedMethodIsNotAllowed() throws Exception {
        mvc.perform(delete("/api/game/sessions").header("Authorization", token(data.player())))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
