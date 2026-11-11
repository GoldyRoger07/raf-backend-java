package com.raikiservice.raf.game;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.PlayerAppService.PlayerAppConfig;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.service.QuizAccess;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Interrupteur global des quiz solo (App joueur) : coupe, tous les quiz solo ouverts sont masques. */
class SoloGlobalHideTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private PlayerAppService playerAppService;
    @Autowired private GameService gameService;
    @Autowired private QuizService quizService;
    @Autowired private QuizAccess quizAccess;
    @Autowired private UserRepository userRepository;

    /** Reglage global, partage par toute la suite : toujours remis comme avant. */
    @AfterEach
    void showSoloQuizzesAgain() {
        setVisible(true);
    }

    private void setVisible(boolean visible) {
        playerAppService.update(null, new PlayerAppConfig(null, null, null, null, null, visible));
    }

    private boolean listed(Long quizId, User viewer, boolean privileged) {
        return quizService.visibleToPlayers(GameMode.SOLO, privileged, viewer.getId(), PageRequest.of(0, 500))
                .getContent().stream().map(Quiz::getId).anyMatch(quizId::equals);
    }

    @Test
    void switchingOffHidesEveryOpenSoloQuizUntilSwitchedOnAgain() {
        Long categoryId = data.categoryWithQuestions(30);
        Long quizId = data.soloQuiz(categoryId, 3, 0);
        User player = data.player();
        User tester = data.player();
        tester.setTest(true);
        userRepository.save(tester);

        assertThat(playerAppService.get().soloQuizzesVisible()).isTrue();
        assertThat(listed(quizId, player, false)).isTrue();

        setVisible(false);

        assertThat(listed(quizId, player, false)).isFalse();
        assertThat(listed(quizId, tester, true)).isTrue();
        assertThatThrownBy(() -> quizAccess.assertVisible(quizService.require(quizId), player.getId()))
                .isInstanceOf(ApiException.class);
        // Pour qui n'y a jamais joue, le quiz n'existe pas, comme un quiz masque.
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> gameService.start(player.getId(), quizId, IP, UA, null));
        // Le quiz lui-meme n'a pas change.
        assertThat(quizService.require(quizId).isHidden()).isFalse();

        setVisible(true);

        assertThat(listed(quizId, player, false)).isTrue();
        gameService.start(player.getId(), quizId, IP, UA, null);
    }

    @Test
    void aGameAlreadyStartedGoesOnAfterTheSwitchIsCut() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(30), 3, 0);
        User player = data.player();
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, null);

        setVisible(false);

        // Relancer rend la partie en cours ; les reponses passent jusqu'au bout.
        assertThat(gameService.start(player.getId(), quizId, IP, UA, null).sessionId()).isEqualTo(game.sessionId());
        GameStartResponse.CurrentQuestion current = game.current();
        while (current != null) {
            Long questionId = current.question().id();
            current = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, data.correctChoice(questionId))).next();
        }
        // Il a joue : il garde l'acces a son quiz (resultat, corrige).
        quizAccess.assertVisible(quizService.require(quizId), player.getId());
    }
}
