package com.raikiservice.raf.quiz;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.SoloLeaderboardService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.web.dto.SoloTermsDto;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prix, prime et nombre de questions d'un quiz solo ouvert : les parties passees et en cours
 * gardent leurs valeurs, les suivantes prennent les nouvelles.
 */
class SoloTermsChangeTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private QuizService quizService;
    @Autowired private GameService gameService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private SoloLeaderboardService leaderboard;
    @Autowired private JdbcTemplate jdbc;

    private User funded(int credits) {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, credits);
        return player;
    }

    /** Joue jusqu'au bout, juste partout sauf a la position {@code wrongAt} (0 = aucune erreur). */
    private GameResultResponse finish(User player, GameStartResponse game, int wrongAt) {
        GameStartResponse.CurrentQuestion current = game.current();
        GameResultResponse result = null;
        int position = 1;
        while (current != null) {
            Long id = current.question().id();
            Long choice = position == wrongAt ? data.wrongChoice(id) : data.correctChoice(id);
            var turn = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(id, choice));
            current = turn.next();
            result = turn.result();
            position++;
        }
        return result;
    }

    private GameResultResponse play(User player, Long quizId, int wrongAt) {
        return finish(player, gameService.start(player.getId(), quizId, IP, UA, null), wrongAt);
    }

    private Quiz quiz(Long id) {
        return quizRepository.findById(id).orElseThrow();
    }

    private SoloTermsDto terms(int price, long prize, int questions, boolean confirm) {
        return new SoloTermsDto(price, prize, questions, confirm);
    }

    @Test
    void pastGamesKeepTheirValuesAndNextGamesUseTheNewOnes() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(50), 3);
        User before = funded(TestData.SOLO_ENTRY);
        GameResultResponse old = play(before, quizId, 0);
        assertThat(old.won()).isTrue();

        quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY + 2, TestData.SOLO_PRIZE + 10, 4, true));

        User after = funded(TestData.SOLO_ENTRY + 2);
        GameResultResponse fresh = play(after, quizId, 0);
        assertThat(fresh.won()).isTrue();

        // Ancienne partie : prix, prime et nombre de questions d'origine, gains inchanges.
        assertThat(jdbc.queryForMap("SELECT entry_price_credits, prize_htg FROM game_sessions WHERE id = ?",
                old.sessionId())).containsValues(TestData.SOLO_ENTRY, TestData.SOLO_PRIZE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_session_questions WHERE session_id = ?",
                Integer.class, old.sessionId())).isEqualTo(3);
        assertThat(data.balance(before.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);

        // Nouvelle partie : nouvelles regles.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_session_questions WHERE session_id = ?",
                Integer.class, fresh.sessionId())).isEqualTo(4);
        assertThat(data.balance(after.getId(), BalanceType.CREDIT)).isZero();
        assertThat(data.balance(after.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE + 10);
    }

    @Test
    void aGameInProgressKeepsTheTermsItStartedWith() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(50), 3);
        User player = funded(TestData.SOLO_ENTRY);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, null);

        quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY, TestData.SOLO_PRIZE * 3, 3, true));

        assertThat(finish(player, game, 0).won()).isTrue();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);
    }

    @Test
    void anUnprofitableChangeNeedsAConfirmation() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(50), 3);

        assertApiError(ErrorCode.SOLO_TERMS_UNPROFITABLE,
                () -> quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY, 100_000, 3, false)));
        assertThat(quiz(quizId).getSoloPrizeHtg()).isEqualTo(TestData.SOLO_PRIZE);

        quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY, 100_000, 3, true));
        assertThat(quiz(quizId).getSoloPrizeHtg()).isEqualTo(100_000);
    }

    @Test
    void moreQuestionsNeedABigEnoughPool() {
        // Vivier de 30 questions : 3 par partie, pas 4 (ratio de 10).
        Long quizId = data.soloQuiz(data.categoryWithQuestions(30), 3);
        assertApiError(ErrorCode.QUIZ_POOL_INSUFFICIENT,
                () -> quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY, TestData.SOLO_PRIZE, 4, true)));
        assertThat(quiz(quizId).getQuestionsPerGame()).isEqualTo(3);
    }

    @Test
    void theLeaderboardRestartsOnlyWhenTheNumberOfQuestionsChanges() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(50), 3);
        User early = funded(TestData.SOLO_ENTRY);
        play(early, quizId, 2);
        Long viewer = data.player().getId();

        // Prix seul : le classement garde ses joueurs.
        quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY + 1, TestData.SOLO_PRIZE, 3, true));
        assertThat(leaderboard.leaderboard(quiz(quizId), viewer).players()).isEqualTo(1);
        assertThat(quiz(quizId).getLeaderboardSince()).isNull();

        // Nombre de questions : repart de zero.
        quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY + 1, TestData.SOLO_PRIZE, 4, true));
        assertThat(quiz(quizId).getLeaderboardSince()).isNotNull();
        assertThat(leaderboard.leaderboard(quiz(quizId), viewer).players()).isZero();

        play(funded(TestData.SOLO_ENTRY + 1), quizId, 1);
        assertThat(leaderboard.leaderboard(quiz(quizId), viewer).players()).isEqualTo(1);
    }

    @Test
    void aPlayerWhoSawTheOldPriceIsNotCharged() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(50), 3);
        User player = funded(TestData.SOLO_ENTRY + 3);

        quizService.changeSoloTerms(null, quizId, terms(TestData.SOLO_ENTRY + 3, TestData.SOLO_PRIZE, 3, true));

        // Ecran ouvert avant le changement : ancien prix affiche.
        assertApiError(ErrorCode.SOLO_TERMS_CHANGED, () -> gameService.start(player.getId(), quizId, IP, UA, null,
                new GameService.SeenTerms(TestData.SOLO_ENTRY, TestData.SOLO_PRIZE, 3)));
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(TestData.SOLO_ENTRY + 3);

        // Ecran a jour : la partie se lance au nouveau prix.
        gameService.start(player.getId(), quizId, IP, UA, null,
                new GameService.SeenTerms(TestData.SOLO_ENTRY + 3, TestData.SOLO_PRIZE, 3));
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
    }

    @Test
    void onlyAnOpenSoloQuizIsChangedThisWay() {
        Long categoryId = data.categoryWithQuestions(50);
        Long groupQuiz = data.runningQuiz(categoryId, 3, 0, 1, data.player());
        assertApiError(ErrorCode.QUIZ_INVALID_STATE,
                () -> quizService.changeSoloTerms(null, groupQuiz, terms(1, 10, 3, true)));
    }
}
