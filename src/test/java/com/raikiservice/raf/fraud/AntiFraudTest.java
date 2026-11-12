package com.raikiservice.raf.fraud;

import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.fraud.service.AntiFraudService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameReviewResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Anti-fraude : pas de sans-faute en solo pour un joueur vise, rien ne change pour les autres. */
class AntiFraudTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";
    private static final int QUESTIONS = 5;

    @Autowired private AntiFraudService antiFraud;
    @Autowired private GameService gameService;
    @Autowired private UserRepository userRepository;

    /** Joue toute la partie, juste partout sauf a la position {@code wrongAt} (0 = aucune erreur). */
    private GameResultResponse play(User player, Long quizId, int wrongAt) {
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
        return data.soloQuiz(data.categoryWithQuestions(60), QUESTIONS);
    }

    private User fundedPlayer() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        return player;
    }

    @Test
    void aTargetedPlayerCannotMakeAPerfectGame() {
        User player = fundedPlayer();
        antiFraud.addTarget(null, player.getId(), "Suspect");

        GameResultResponse result = play(player, paidSoloQuiz(), 0);

        assertThat(result.correctCount()).isBetween(QUESTIONS - AntiFraudService.MAX_VOIDED, QUESTIONS - 1);
        assertThat(result.won()).isFalse();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isZero();

        // Le back-office garde la vraie correction.
        GameReviewResponse review = gameService.review(result.sessionId());
        long voided = review.items().stream().filter(GameReviewResponse.ReviewItem::antiFraudVoided).count();
        assertThat(review.antiFraud()).isTrue();
        assertThat(voided).isEqualTo(QUESTIONS - result.correctCount());
        assertThat(review.realCorrectCount()).isEqualTo(QUESTIONS);
        assertThat(review.realScore()).isGreaterThan(review.score());
        assertThat(review.items()).filteredOn(GameReviewResponse.ReviewItem::antiFraudVoided)
                .allSatisfy(item -> {
                    assertThat(item.correct()).isFalse();
                    assertThat(item.chosenChoiceId()).isEqualTo(item.correctChoiceId());
                });
    }

    @Test
    void aGameWithAnErrorIsLeftUntouched() {
        User player = fundedPlayer();
        antiFraud.addTarget(null, player.getId(), "Suspect");

        GameResultResponse result = play(player, paidSoloQuiz(), 2);

        assertThat(result.correctCount()).isEqualTo(QUESTIONS - 1);
        assertThat(gameService.review(result.sessionId()).items())
                .noneMatch(GameReviewResponse.ReviewItem::antiFraudVoided);
    }

    @Test
    void otherPlayersStillWinAndRemovalRestoresNormalGames() {
        User other = fundedPlayer();
        GameResultResponse result = play(other, paidSoloQuiz(), 0);
        assertThat(result.correctCount()).isEqualTo(QUESTIONS);
        assertThat(result.won()).isTrue();
        assertThat(gameService.review(result.sessionId()).antiFraud()).isFalse();

        User player = fundedPlayer();
        antiFraud.addTarget(null, player.getId(), "Suspect");
        antiFraud.removeTarget(null, player.getId());
        assertThat(play(player, paidSoloQuiz(), 0).won()).isTrue();
    }

    @Test
    void allPlayersSwitchSparesStaffButReachesTestAccounts() {
        antiFraud.setAllPlayers(null, true);
        try {
            assertThat(antiFraud.appliesTo(data.player())).isTrue();
            assertThat(antiFraud.appliesTo(data.admin())).isFalse();
            assertThat(antiFraud.appliesTo(data.superAdmin())).isFalse();
            assertThat(antiFraud.appliesTo(data.moderator())).isFalse();

            User tester = data.player();
            tester.setTest(true);
            userRepository.save(tester);
            GameResultResponse result = play(tester, paidSoloQuiz(), 0);
            assertThat(result.correctCount()).isLessThan(QUESTIONS);
        } finally {
            antiFraud.setAllPlayers(null, false);
        }
        assertThat(antiFraud.appliesTo(data.player())).isFalse();
    }

    @Test
    void stateIsReadAtLaunch() {
        User player = fundedPlayer();
        Long quizId = paidSoloQuiz();
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, null);
        antiFraud.addTarget(null, player.getId(), "Ajoute en cours de partie");

        GameStartResponse.CurrentQuestion current = game.current();
        GameResultResponse result = null;
        while (current != null) {
            Long questionId = current.question().id();
            var turn = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, data.correctChoice(questionId)));
            current = turn.next();
            result = turn.result();
        }
        assertThat(result.won()).isTrue();
    }

    @Test
    void noteIsRequiredAndStaffCannotBeListed() {
        User player = data.player();
        assertThatThrownBy(() -> antiFraud.addTarget(null, player.getId(), " ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> antiFraud.addTarget(null, data.admin().getId(), "Note"))
                .isInstanceOf(ApiException.class);

        antiFraud.addTarget(null, player.getId(), "Premiere note");
        antiFraud.addTarget(null, player.getId(), "Note mise a jour");
        assertThat(antiFraud.targets()).filteredOn(t -> t.playerId().equals(player.getId()))
                .singleElement().satisfies(t -> assertThat(t.note()).isEqualTo("Note mise a jour"));
    }

    @Test
    void voidsBetweenOneAndThreeAnswers() {
        for (int i = 0; i < 200; i++) {
            assertThat(antiFraud.pickVoided(List.of(1, 2, 3, 4, 5, 6))).hasSizeBetween(1, 3).doesNotHaveDuplicates();
            assertThat(antiFraud.pickVoided(List.of(1, 2))).hasSizeBetween(1, 2);
        }
        assertThat(antiFraud.pickVoided(List.of())).isEmpty();
    }
}
