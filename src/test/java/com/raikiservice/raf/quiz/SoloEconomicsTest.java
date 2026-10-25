package com.raikiservice.raf.quiz;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.SoloEconomicsService;
import com.raikiservice.raf.quiz.service.SoloEconomicsService.SoloEconomics;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.service.WalletService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Bilan d'un quiz solo : taux de sans-faute estime sur le vivier, prix maximal
 * supportable, puis taux et marge observes une fois le quiz ouvert.
 */
class SoloEconomicsTest extends IntegrationTest {

    @Autowired private SoloEconomicsService soloEconomicsService;
    @Autowired private QuizService quizService;
    @Autowired private GameService gameService;
    @Autowired private WalletService walletService;

    @Test
    void anUnplayedPoolFallsBackOnTheDeclaredDifficulty() {
        // Questions MEDIUM jamais servies : reussite a priori de 70 %.
        Long categoryId = data.categoryWithQuestions(30);
        long entry = 10 * walletService.htgRate();

        SoloEconomics economics = soloEconomicsService.preview(categoryId, 10, 500, 10);

        assertThat(economics.poolQuestions()).isEqualTo(30);
        assertThat(economics.questionsWithStats()).isZero();
        assertThat(economics.estimatedAccuracy()).isCloseTo(0.70, within(1e-9));
        assertThat(economics.estimatedWinRate()).isCloseTo(Math.pow(0.70, 10), within(1e-9));
        assertThat(economics.breakEvenWinRate()).isCloseTo(entry / 500.0, within(1e-9));
        assertThat(economics.breakEvenPrizeHtg())
                .isEqualTo((long) Math.floor(entry / Math.pow(0.70, 10)));
        assertThat(economics.expectedMarginPerGameHtg())
                .isEqualTo(Math.round(entry - Math.pow(0.70, 10) * 500));
        assertThat(economics.gamesPlayed()).isNull();
    }

    @Test
    void moreQuestionsMakeTheSamePrizeSafer() {
        Long categoryId = data.categoryWithQuestions(30);

        SoloEconomics few = soloEconomicsService.preview(categoryId, 1, 5_000, 3);
        SoloEconomics many = soloEconomicsService.preview(categoryId, 1, 5_000, 20);

        assertThat(few.profitable()).isFalse();
        assertThat(many.estimatedWinRate()).isLessThan(few.estimatedWinRate());
        assertThat(many.breakEvenPrizeHtg()).isGreaterThan(few.breakEvenPrizeHtg());
    }

    @Test
    void aFreeQuizIsNeverProfitable() {
        Long categoryId = data.categoryWithQuestions(30);

        SoloEconomics economics = soloEconomicsService.preview(categoryId, 0, 200, 10);

        assertThat(economics.entryPriceHtg()).isZero();
        assertThat(economics.breakEvenWinRate()).isZero();
        assertThat(economics.profitable()).isFalse();
        assertThat(economics.expectedMarginPerGameHtg()).isNegative();
    }

    @Test
    void anOpenQuizReportsWhatItActuallyEarned() {
        Long categoryId = data.categoryWithQuestions(30);
        Long quizId = data.soloQuiz(categoryId, 3);
        long entry = TestData.SOLO_ENTRY * walletService.htgRate();

        User winner = funded();
        User loser = funded();
        play(winner, gameService.start(winner.getId(), quizId, "127.0.0.1", "junit"), 3);
        play(loser, gameService.start(loser.getId(), quizId, "127.0.0.1", "junit"), 1);

        SoloEconomics economics = soloEconomicsService.forQuiz(quizService.require(quizId));

        assertThat(economics.poolQuestions()).isEqualTo(30);
        assertThat(economics.gamesPlayed()).isEqualTo(2);
        assertThat(economics.gamesWon()).isEqualTo(1);
        assertThat(economics.observedWinRate()).isEqualTo(0.5);
        assertThat(economics.observedMarginHtg()).isEqualTo(2 * entry - TestData.SOLO_PRIZE);
    }

    private User funded() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        return player;
    }

    /** Joue toute la partie avec {@code correct} bonnes reponses, les premieres. */
    private void play(User player, GameStartResponse game, int correct) {
        GameStartResponse.CurrentQuestion current = game.current();
        for (int i = 0; current != null; i++) {
            Long questionId = current.question().id();
            Long choiceId = i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            GameTurnResponse turn = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, choiceId));
            current = turn.next();
        }
    }
}
