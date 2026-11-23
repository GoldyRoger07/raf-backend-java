package com.raikiservice.raf.quiz;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.SoloBreakerService;
import com.raikiservice.raf.quiz.service.SoloBreakerService.Config;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.service.WalletService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disjoncteur de rentabilite : un quiz solo qui verse plus qu'il ne rapporte est masque
 * (jamais ferme), et repart de zero quand un admin le demasque.
 *
 * <p>Quiz de test : entree a {@link TestData#SOLO_ENTRY} credits, sans-faute a
 * {@link TestData#SOLO_PRIZE} HTG. Chaque sans-faute verse donc plus que sa recette.
 */
class SoloBreakerTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";
    private static final int QUESTIONS = 4;

    @Autowired private SoloBreakerService breaker;
    @Autowired private GameService gameService;
    @Autowired private QuizService quizService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private WalletService walletService;

    /** Reglage global : coupe apres chaque test, pour ne pas toucher les autres. */
    @AfterEach
    void switchOff() {
        breaker.setConfig(null, new Config(false, SoloBreakerService.DEFAULT_WINDOW_GAMES,
                SoloBreakerService.DEFAULT_MIN_GAMES, SoloBreakerService.DEFAULT_MAX_PAYOUT_PERCENT));
    }

    private void enable(int window, int minGames, int maxPercent) {
        breaker.setConfig(null, new Config(true, window, minGames, maxPercent));
    }

    /** Joue toute la partie, juste partout sauf a la position {@code wrongAt} (0 = aucune erreur). */
    private GameResultResponse play(Long quizId, int wrongAt) {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, null);
        GameStartResponse.CurrentQuestion current = game.current();
        GameResultResponse result = null;
        int position = 1;
        while (current != null) {
            Long questionId = current.question().id();
            Long choice = position == wrongAt ? data.wrongChoice(questionId) : data.correctChoice(questionId);
            var turn = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(questionId, choice));
            current = turn.next();
            result = turn.result();
            position++;
        }
        return result;
    }

    private Long paidSoloQuiz() {
        return data.soloQuiz(data.categoryWithQuestions(40), QUESTIONS);
    }

    private Quiz reload(Long quizId) {
        return quizRepository.findById(quizId).orElseThrow();
    }

    @Test
    void aLosingQuizIsHiddenNotClosed() {
        enable(10, 2, 100);
        Long quizId = paidSoloQuiz();

        assertThat(play(quizId, 0).won()).isTrue();
        // Une seule partie : sous le minimum, rien ne se passe.
        assertThat(breaker.checkAll().trippedQuizIds()).doesNotContain(quizId);
        assertThat(reload(quizId).isHidden()).isFalse();

        assertThat(play(quizId, 0).won()).isTrue();
        assertThat(breaker.checkAll().trippedQuizIds()).contains(quizId);

        Quiz quiz = reload(quizId);
        assertThat(quiz.isHidden()).isTrue();
        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.RUNNING);
        assertThat(quiz.getBreakerTrippedAt()).isNotNull();

        // Masque : introuvable pour un nouveau joueur, aucune partie possible.
        User late = data.player();
        data.credit(late.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> gameService.start(late.getId(), quizId, IP, UA, null));

        // Deja masque : une nouvelle verification ne le compte pas deux fois.
        assertThat(breaker.checkAll().trippedQuizIds()).doesNotContain(quizId);
    }

    @Test
    void unhidingRearmsFromZero() {
        enable(10, 2, 100);
        Long quizId = paidSoloQuiz();
        play(quizId, 0);
        play(quizId, 0);
        assertThat(breaker.checkAll().trippedQuizIds()).contains(quizId);

        quizService.setHidden(null, quizId, false);
        Quiz quiz = reload(quizId);
        assertThat(quiz.isHidden()).isFalse();
        assertThat(quiz.getBreakerTrippedAt()).isNull();
        assertThat(quiz.getBreakerCountedSince()).isNotNull();

        // Les anciennes parties ne comptent plus : pas de nouvelle coupure tout de suite.
        assertThat(breaker.checkAll().trippedQuizIds()).doesNotContain(quizId);
        assertThat(reload(quizId).isHidden()).isFalse();
    }

    @Test
    void aProfitableQuizStaysOpen() {
        enable(10, 2, 100);
        Long quizId = paidSoloQuiz();
        play(quizId, 1);
        play(quizId, 2);
        play(quizId, 3);

        assertThat(breaker.checkAll().trippedQuizIds()).doesNotContain(quizId);
        assertThat(reload(quizId).isHidden()).isFalse();
    }

    @Test
    void theLimitIsAPercentOfRevenue() {
        // 1 sans-faute sur 3 parties : 40 HTG verses pour 3 entrees.
        Long quizId = paidSoloQuiz();
        play(quizId, 0);
        play(quizId, 1);
        play(quizId, 2);
        long revenue = 3L * TestData.SOLO_ENTRY * htgRate();
        long percent = Math.round(TestData.SOLO_PRIZE * 100.0 / revenue);

        enable(10, 3, (int) percent + 1);
        assertThat(breaker.checkAll().trippedQuizIds()).doesNotContain(quizId);

        enable(10, 3, (int) percent - 1);
        assertThat(breaker.checkAll().trippedQuizIds()).contains(quizId);
    }

    @Test
    void nothingHappensWhenSwitchedOff() {
        Long quizId = paidSoloQuiz();
        play(quizId, 0);
        play(quizId, 0);

        assertThat(breaker.checkAll().trippedQuizIds()).isEmpty();
        assertThat(reload(quizId).isHidden()).isFalse();
    }

    @Test
    void freeQuizzesAreNeverCut() {
        enable(10, 2, 100);
        Long quizId = data.soloQuiz(data.categoryWithQuestions(40), QUESTIONS, 0);
        play(quizId, 0);
        play(quizId, 0);

        assertThat(breaker.checkAll().trippedQuizIds()).doesNotContain(quizId);
        assertThat(reload(quizId).isHidden()).isFalse();
    }

    @Test
    void theOverviewShowsTheWindow() {
        enable(10, 2, 100);
        Long quizId = paidSoloQuiz();
        play(quizId, 0);
        play(quizId, 1);

        SoloBreakerService.QuizRow row = breaker.overview().quizzes().stream()
                .filter(r -> r.quizId().equals(quizId)).findFirst().orElseThrow();
        assertThat(row.games()).isEqualTo(2);
        assertThat(row.perfectGames()).isEqualTo(1);
        assertThat(row.prizesHtg()).isEqualTo(TestData.SOLO_PRIZE);
        assertThat(row.revenueHtg()).isEqualTo(2L * TestData.SOLO_ENTRY * htgRate());
    }

    private long htgRate() {
        return walletService.htgRate();
    }
}
