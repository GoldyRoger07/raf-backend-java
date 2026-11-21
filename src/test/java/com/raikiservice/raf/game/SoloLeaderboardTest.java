package com.raikiservice.raf.game;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Classement d'un quiz solo : meilleur score par joueur, gains sur le quiz, noms masques. */
class SoloLeaderboardTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private GameService gameService;
    @Autowired private JdbcTemplate jdbc;

    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    @Test
    void oneLinePerPlayerWithBestScoreWinningsAndMaskedNames() throws Exception {
        Long quizId = data.soloQuiz(categoryId, 3);
        User champion = funded(1);
        User second = funded(2);
        User third = funded(1);
        User viewer = funded(0);
        User staff = data.admin();
        data.credit(staff.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);

        play(champion, quizId, 3);
        play(second, quizId, 2);
        // Deuxieme partie de « second » un autre jour, moins bonne : pas de doublon, le meilleur reste.
        jdbc.update("UPDATE game_sessions SET solo_day = DATE_SUB(solo_day, INTERVAL 1 DAY) "
                + "WHERE user_id = ? AND quiz_id = ?", second.getId(), quizId);
        jdbc.update("UPDATE wallet_transactions SET idempotency_key = CONCAT(idempotency_key, ':veille') "
                + "WHERE idempotency_key LIKE CONCAT('solo:', ?, ':%')", second.getId());
        play(second, quizId, 1);
        play(third, quizId, 1);
        play(staff, quizId, 3);

        mvc.perform(get("/api/quizzes/" + quizId + "/solo-leaderboard").header("Authorization", token(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionsPerGame").value(3))
                .andExpect(jsonPath("$.players").value(3))
                .andExpect(jsonPath("$.perfects").value(1))
                .andExpect(jsonPath("$.wonHtg").value(TestData.SOLO_PRIZE))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].bestCorrect").value(3))
                .andExpect(jsonPath("$.entries[0].wonHtg").value(TestData.SOLO_PRIZE))
                .andExpect(jsonPath("$.entries[0].displayName").value(masked(champion)))
                .andExpect(jsonPath("$.entries[1].bestCorrect").value(2))
                .andExpect(jsonPath("$.entries[1].wonHtg").value(0))
                .andExpect(jsonPath("$.entries[2].bestCorrect").value(1))
                .andExpect(jsonPath("$.me").doesNotExist());

        // Le joueur qui a joue voit sa place, marquee.
        mvc.perform(get("/api/quizzes/" + quizId + "/solo-leaderboard").header("Authorization", token(second)))
                .andExpect(jsonPath("$.me.rank").value(2))
                .andExpect(jsonPath("$.me.me").value(true))
                .andExpect(jsonPath("$.entries[1].me").value(true));
    }

    @Test
    void aGroupQuizHasNoSoloLeaderboard() throws Exception {
        Long groupQuiz = data.runningQuiz(categoryId, 3, 100, 1, data.player());
        mvc.perform(get("/api/quizzes/" + groupQuiz + "/solo-leaderboard").header("Authorization", token(data.player())))
                .andExpect(status().isNotFound());
    }

    /** « Joueur12 Test » donne « Jo*** Te*** ». */
    private static String masked(User user) {
        return user.getFirstName().substring(0, 2) + "*** " + user.getLastName().substring(0, 2) + "***";
    }

    private User funded(int games) {
        User player = data.player();
        if (games > 0) {
            data.credit(player.getId(), BalanceType.CREDIT, (long) TestData.SOLO_ENTRY * games);
        }
        return player;
    }

    private GameResultResponse play(User player, Long quizId, int correct) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        GameStartResponse.CurrentQuestion current = game.current();
        GameTurnResponse turn = null;
        for (int i = 0; current != null; i++) {
            Long questionId = current.question().id();
            Long choiceId = i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            turn = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(questionId, choiceId));
            current = turn.next();
        }
        return turn.result();
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
