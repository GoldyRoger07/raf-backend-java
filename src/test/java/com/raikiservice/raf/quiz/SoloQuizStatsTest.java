package com.raikiservice.raf.quiz;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.Filters;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.PerfectFilter;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.Period;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.ResultFilter;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.Row;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.Sort;
import com.raikiservice.raf.quiz.service.SoloQuizStatsService.StatsPage;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.service.WalletService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Activite des quiz solo au back-office : chiffres, filtres et tri. */
class SoloQuizStatsTest extends IntegrationTest {

    @Autowired private SoloQuizStatsService statsService;
    @Autowired private GameService gameService;
    @Autowired private WalletService walletService;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void theActivityOfEachSoloQuizIsCountedAndFiltered() throws Exception {
        // Sujet propre au test : les filtres par sujet isolent ses quiz des autres tests.
        Long categoryId = data.categoryWithQuestions(30);
        Long played = data.soloQuiz(categoryId, 3);
        Long untouched = data.soloQuiz(categoryId, 3);

        play(played, 3);
        play(played, 2);
        // Un compte de test ne fausse pas les chiffres.
        User tester = funded();
        play(tester, played, 3);
        userRepository.save(userRepository.findById(tester.getId()).map(u -> {
            u.setTest(true);
            return u;
        }).orElseThrow());

        Row row = row(stats(categoryId, PerfectFilter.ALL, ResultFilter.ALL, Sort.MOST_PLAYED), played);
        long rate = walletService.htgRate();
        assertThat(row.games()).isEqualTo(2);
        assertThat(row.players()).isEqualTo(2);
        assertThat(row.perfectGames()).isEqualTo(1);
        assertThat(row.perfectRate()).isEqualTo(50.0);
        assertThat(row.averageCorrect()).isEqualTo(2.5);
        assertThat(row.revenueHtg()).isEqualTo(2L * TestData.SOLO_ENTRY * rate);
        assertThat(row.prizesPaidHtg()).isEqualTo(TestData.SOLO_PRIZE);
        assertThat(row.resultHtg()).isEqualTo(2L * TestData.SOLO_ENTRY * rate - TestData.SOLO_PRIZE);
        assertThat(row.lastPlayedAt()).isNotNull();

        StatsPage all = stats(categoryId, PerfectFilter.ALL, ResultFilter.ALL, Sort.MOST_PLAYED);
        assertThat(all.page().getContent()).extracting(Row::id).containsExactly(played, untouched);
        assertThat(all.summary().quizzes()).isEqualTo(2);
        assertThat(all.summary().games()).isEqualTo(2);
        assertThat(all.summary().perfectGames()).isEqualTo(1);

        assertThat(ids(stats(categoryId, PerfectFilter.WITH, ResultFilter.ALL, Sort.RECENT))).containsExactly(played);
        assertThat(ids(stats(categoryId, PerfectFilter.WITHOUT, ResultFilter.ALL, Sort.RECENT))).isEmpty();
        assertThat(ids(stats(categoryId, PerfectFilter.NEVER_PLAYED, ResultFilter.ALL, Sort.RECENT)))
                .containsExactly(untouched);

        // Prix (40 HTG) contre deux entrees de 5 credits : le resultat depend du taux du credit.
        boolean profit = row.resultHtg() > 0;
        assertThat(ids(stats(categoryId, PerfectFilter.ALL, profit ? ResultFilter.PROFIT : ResultFilter.LOSS,
                Sort.RECENT))).containsExactly(played);
        assertThat(ids(stats(categoryId, PerfectFilter.ALL, profit ? ResultFilter.LOSS : ResultFilter.PROFIT,
                Sort.RECENT))).isEmpty();

        mvc.perform(get("/api/admin/quizzes/solo/stats")
                        .param("status", "RUNNING").param("categoryId", String.valueOf(categoryId))
                        .param("perfect", "WITH")
                        .header("Authorization", token(data.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.quizzes").value(1))
                .andExpect(jsonPath("$.page.content[0].id").value(played))
                .andExpect(jsonPath("$.page.content[0].perfectGames").value(1));
        mvc.perform(get("/api/admin/quizzes/solo/stats").header("Authorization", token(data.moderator())))
                .andExpect(status().isForbidden());
    }

    // --- Outils -------------------------------------------------------------------

    private StatsPage stats(Long categoryId, PerfectFilter perfect, ResultFilter result, Sort sort) {
        return statsService.stats(new Filters(QuizStatus.RUNNING, Period.ALL_TIME, null, categoryId, perfect,
                result, sort), PageRequest.of(0, 20));
    }

    private static java.util.List<Long> ids(StatsPage stats) {
        return stats.page().getContent().stream().map(Row::id).toList();
    }

    private static Row row(StatsPage stats, Long quizId) {
        return stats.page().getContent().stream().filter(r -> r.id().equals(quizId)).findFirst().orElseThrow();
    }

    private User funded() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        return player;
    }

    private GameResultResponse play(Long quizId, int correct) {
        return play(funded(), quizId, correct);
    }

    /** Joue toute la partie avec {@code correct} bonnes reponses, les premieres. */
    private GameResultResponse play(User player, Long quizId, int correct) {
        GameStartResponse game = gameService.start(player.getId(), quizId, "127.0.0.1", "junit");
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
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
