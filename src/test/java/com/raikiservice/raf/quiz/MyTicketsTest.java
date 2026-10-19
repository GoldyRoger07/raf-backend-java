package com.raikiservice.raf.quiz;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/tickets : l'application joueur liste ses tickets sans parcourir chaque quiz. */
class MyTicketsTest extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void aPlayerListsOnlyTheirOwnTicketsWithTheQuizStatus() throws Exception {
        User alice = data.player();
        User bob = data.player();
        Long category = data.categoryWithQuestions(30);
        // Deux quiz demarres : Alice a 2 tickets sur le premier, Bob 1 sur le second.
        Long running = data.runningQuiz(category, 3, 1000, 2, alice);
        data.runningQuiz(category, 3, 1000, 1, bob);

        mvc.perform(get("/api/tickets").header("Authorization", token(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].quizId").value(running))
                .andExpect(jsonPath("$.content[0].quizStatus").value("RUNNING"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));

        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
