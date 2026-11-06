package com.raikiservice.raf.game.service;

import java.time.LocalDate;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.game.web.dto.PlayerStatsResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Statistiques personnelles de l'onglet Actualite. */
class PlayerStatsTest extends IntegrationTest {

    @Autowired private PlayerStatsService playerStatsService;
    @Autowired private GameService gameService;

    @Test
    void aNewPlayerHasEmptyStats() {
        PlayerStatsResponse stats = playerStatsService.statsOf(data.player().getId());

        assertThat(stats.gamesPlayed()).isZero();
        assertThat(stats.accuracyPercent()).isNull();
        assertThat(stats.bestRank()).isNull();
        assertThat(stats.currentStreakDays()).isZero();
        assertThat(stats.favoriteSubject()).isNull();
    }

    @Test
    void soloGamesCountAnswersPerfectGamesWinningsAndTheDayStreak() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 2L * TestData.SOLO_ENTRY);
        playSolo(player, data.soloQuiz(data.categoryWithQuestions(30), 3), 3);
        playSolo(player, data.soloQuiz(data.categoryWithQuestions(30), 3), 1);

        PlayerStatsResponse stats = playerStatsService.statsOf(player.getId());

        assertThat(stats.gamesPlayed()).isEqualTo(2);
        assertThat(stats.soloGames()).isEqualTo(2);
        assertThat(stats.groupGames()).isZero();
        assertThat(stats.correctAnswers()).isEqualTo(4);
        assertThat(stats.answeredQuestions()).isEqualTo(6);
        assertThat(stats.accuracyPercent()).isEqualTo(67);
        assertThat(stats.perfectSoloGames()).isEqualTo(1);
        assertThat(stats.totalWinningsHtg()).isEqualTo(TestData.SOLO_PRIZE);
        assertThat(stats.currentStreakDays()).isEqualTo(1);
        assertThat(stats.bestStreakDays()).isEqualTo(1);
        assertThat(stats.favoriteSubject()).isNotNull();
    }

    @Test
    void theStreakSurvivesUntilTheEndOfTheNextDay() {
        LocalDate today = LocalDate.of(2026, 11, 5);
        List<LocalDate> days = List.of(today.minusDays(1), today.minusDays(2), today.minusDays(3),
                today.minusDays(6), today.minusDays(7));

        assertThat(PlayerStatsService.currentStreak(days, today)).isEqualTo(3);
        assertThat(PlayerStatsService.currentStreak(days, today.plusDays(1))).isZero();
        assertThat(PlayerStatsService.currentStreak(List.of(today), today)).isEqualTo(1);
        assertThat(PlayerStatsService.bestStreak(days)).isEqualTo(3);
        assertThat(PlayerStatsService.bestStreak(List.of())).isZero();
    }

    private GameResultResponse playSolo(User player, Long quizId, int correct) {
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
}
