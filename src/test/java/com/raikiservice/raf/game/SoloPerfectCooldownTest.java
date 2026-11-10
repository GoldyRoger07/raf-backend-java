package com.raikiservice.raf.game;

import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.service.QuizAccess;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quiz solo cache a un joueur pendant un delai apres son sans-faute : liste et acces,
 * comptes de test compris, staff exclu, sans-fautes passes compris, reglable a tout moment.
 */
class SoloPerfectCooldownTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private QuizAccess quizAccess;
    @Autowired private GameService gameService;
    @Autowired private JdbcTemplate jdbc;

    private Long categoryId;
    private Long quizId;

    @BeforeEach
    void soloQuiz() {
        categoryId = data.categoryWithQuestions(30);
        quizId = data.soloQuiz(categoryId, 3);
    }

    /** Joue une partie complete avec {@code correct} bonnes reponses sur 3. */
    private void play(User player, int correct) {
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        GameStartResponse game = gameService.start(player.getId(), quizId, "127.0.0.1", "junit");
        GameStartResponse.CurrentQuestion current = game.current();
        for (int i = 0; current != null; i++) {
            Long questionId = current.question().id();
            Long choiceId = i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            GameTurnResponse turn = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, choiceId));
            current = turn.next();
        }
    }

    private boolean listed(User viewer) {
        List<Long> ids = quizService.viewVisible(GameMode.SOLO, quizAccess.seesTestQuizzes(viewer.getId()),
                viewer.getId(), PageRequest.of(0, 500)).getContent().stream().map(QuizResponse::id).toList();
        return ids.contains(quizId);
    }

    @Test
    void aPerfectGameHidesTheQuizFromThatPlayerOnlyDuringTheDelay() {
        quizService.setPerfectCooldown(null, quizId, 60);
        User champion = data.player();
        User other = data.player();

        play(champion, 3);

        assertThat(listed(champion)).isFalse();
        assertThat(listed(other)).isTrue();
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> gameService.start(champion.getId(), quizId, "127.0.0.1", "junit"));
        // Le staff n'est jamais concerne.
        assertThat(listed(data.admin())).isTrue();

        // Delai ecoule : le sans-faute date de 61 minutes.
        jdbc.update("UPDATE game_sessions SET submitted_at = DATE_SUB(submitted_at, INTERVAL 61 MINUTE) "
                + "WHERE quiz_id = ? AND user_id = ?", quizId, champion.getId());
        assertThat(listed(champion)).isTrue();
    }

    @Test
    void aGameWithAMistakeHidesNothing() {
        quizService.setPerfectCooldown(null, quizId, 60);
        User player = data.player();
        play(player, 2);
        assertThat(listed(player)).isTrue();
    }

    @Test
    void theRuleCountsPastPerfectGamesAndCanBeSwitchedOffAnytime() {
        User champion = data.player();
        play(champion, 3);
        assertThat(listed(champion)).isTrue();      // regle inactive

        quizService.setPerfectCooldown(null, quizId, 24 * 60);   // activee apres coup
        assertThat(listed(champion)).isFalse();
        assertThat(quizService.view(quizId).perfectCooldownMinutes()).isEqualTo(24 * 60);

        quizService.setPerfectCooldown(null, quizId, null);
        assertThat(listed(champion)).isTrue();
    }

    @Test
    void testAccountsAreHiddenToo() {
        quizService.setPerfectCooldown(null, quizId, 60);
        User tester = data.player();
        jdbc.update("UPDATE users SET is_test = TRUE WHERE id = ?", tester.getId());

        play(tester, 3);
        assertThat(listed(tester)).isFalse();
    }

    @Test
    void theRuleOnlyAppliesToSoloQuizzes() {
        Long group = data.publishedQuiz(categoryId, 3, 0, 10, null, null);
        assertApiError(ErrorCode.QUIZ_INVALID_STATE, () -> quizService.setPerfectCooldown(null, group, 60));
    }
}
