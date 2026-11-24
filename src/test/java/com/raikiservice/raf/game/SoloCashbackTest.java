package com.raikiservice.raf.game;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.fraud.service.AntiFraudService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.SoloCashbackService;
import com.raikiservice.raf.game.service.SoloCashbackService.Config;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Cashback du quasi sans-faute en solo : une seule erreur rend une part du prix en credits. */
class SoloCashbackTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";
    private static final int QUESTIONS = 5;

    @Autowired private SoloCashbackService cashback;
    @Autowired private GameService gameService;
    @Autowired private QuizService quizService;
    @Autowired private AntiFraudService antiFraud;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbc;

    /** Reglage global, partage par toute la suite : toujours recoupe. */
    @AfterEach
    void switchOff() {
        cashback.setConfig(null, new Config(false, SoloCashbackService.DEFAULT_PERCENT,
                SoloCashbackService.DEFAULT_DAILY_LIMIT, SoloCashbackService.DEFAULT_MIN_QUESTIONS));
    }

    private void enable(int percent, int dailyLimit) {
        cashback.setConfig(null, new Config(true, percent, dailyLimit, QUESTIONS));
    }

    private Long paidQuiz() {
        return data.soloQuiz(data.categoryWithQuestions(60), QUESTIONS);
    }

    /** Partie jouee avec {@code wrong} mauvaises reponses (les premieres). */
    private GameStartResponse play(User player, Long quizId, int wrong) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, null);
        GameStartResponse.CurrentQuestion current = game.current();
        int served = 0;
        while (current != null) {
            Long questionId = current.question().id();
            Long choice = served++ < wrong ? data.wrongChoice(questionId) : data.correctChoice(questionId);
            current = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(questionId, choice))
                    .next();
        }
        return game;
    }

    private Integer cashbackOf(Long sessionId) {
        return jdbc.queryForObject("SELECT cashback_credits FROM game_sessions WHERE id = ?", Integer.class,
                sessionId);
    }

    private User funded(int games) {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, (long) TestData.SOLO_ENTRY * games);
        return player;
    }

    @Test
    void switchedOffByDefaultNothingIsReturned() {
        assertThat(cashback.config().enabled()).isFalse();
        User player = funded(1);
        GameStartResponse game = play(player, paidQuiz(), 1);
        assertThat(cashbackOf(game.sessionId())).isNull();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
    }

    @Test
    void exactlyOneMistakeReturnsPartOfTheEntryInCredits() {
        enable(50, 3);
        User player = funded(1);
        Long quizId = paidQuiz();
        assertThat(quizService.view(quizId).cashbackPercent()).isEqualTo(50);

        GameStartResponse game = play(player, quizId, 1);

        // 50 % de 5 credits, arrondi a l'unite inferieure.
        assertThat(cashbackOf(game.sessionId())).isEqualTo(2);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(2);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isZero();
        GameResultResponse result = gameService.mySessions(player.getId(), quizId).get(0);
        assertThat(result.won()).isFalse();
        assertThat(result.cashbackCredits()).isEqualTo(2);

        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void twoMistakesOrAPerfectGetNoCashback() {
        enable(50, 3);
        User player = funded(1);
        GameStartResponse twoWrong = play(player, paidQuiz(), 2);
        assertThat(cashbackOf(twoWrong.sessionId())).isNull();

        User winner = funded(1);
        GameStartResponse perfect = play(winner, paidQuiz(), 0);
        assertThat(cashbackOf(perfect.sessionId())).isNull();
        assertThat(data.balance(winner.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);
    }

    @Test
    void aQuizCanOverrideOrSwitchOffTheRate() {
        enable(50, 3);
        Long full = paidQuiz();
        quizService.setCashbackPercent(null, full, 100);
        User player = funded(1);
        play(player, full, 1);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(TestData.SOLO_ENTRY);

        Long none = paidQuiz();
        quizService.setCashbackPercent(null, none, 0);
        assertThat(quizService.view(none).cashbackPercent()).isNull();
        User other = funded(1);
        GameStartResponse game = play(other, none, 1);
        assertThat(cashbackOf(game.sessionId())).isNull();
    }

    @Test
    void theDailyLimitStopsFurtherCashbacks() {
        enable(50, 1);
        User player = funded(2);
        GameStartResponse first = play(player, paidQuiz(), 1);
        GameStartResponse second = play(player, paidQuiz(), 1);
        assertThat(cashbackOf(first.sessionId())).isEqualTo(2);
        assertThat(cashbackOf(second.sessionId())).isNull();
    }

    @Test
    void freeGamesShortGamesAndListedPlayersGetNothing() {
        enable(50, 3);
        User player = data.player();
        GameStartResponse free = play(player, data.soloQuiz(data.categoryWithQuestions(60), QUESTIONS, 0), 1);
        assertThat(cashbackOf(free.sessionId())).isNull();

        User shortGame = funded(1);
        Long shortQuiz = data.soloQuiz(data.categoryWithQuestions(60), QUESTIONS - 1);
        assertThat(quizService.view(shortQuiz).cashbackPercent()).isNull();
        GameStartResponse game = play(shortGame, shortQuiz, 1);
        assertThat(cashbackOf(game.sessionId())).isNull();

        User suspect = funded(1);
        antiFraud.addTarget(null, suspect.getId(), "Suspect");
        GameStartResponse listed = play(suspect, paidQuiz(), 1);
        assertThat(cashbackOf(listed.sessionId())).isNull();
    }

    @Test
    void aPerfectTurnedIntoAMistakeByTheAntiFraudGetsNothing() {
        enable(50, 3);
        Long quizId = paidQuiz();
        quizService.setAntiFraud(null, quizId, true);
        User player = funded(1);
        GameStartResponse game = play(player, quizId, 0);
        assertThat(cashbackOf(game.sessionId())).isNull();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
    }
}
