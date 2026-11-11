package com.raikiservice.raf.fraud;

import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.PlayerAppService.PlayerAppConfig;
import com.raikiservice.raf.fraud.service.FraudProfileService;
import com.raikiservice.raf.fraud.service.FraudProfileService.ProfileRequest;
import com.raikiservice.raf.fraud.service.SoloAdjustment;
import com.raikiservice.raf.game.service.FirstSoloOfferService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Profils fraudeurs : parties solo durcies, visibles, gain inchange, groupe lie compris. */
class FraudProfileTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private FraudProfileService fraud;
    @Autowired private GameService gameService;
    @Autowired private QuizService quizService;
    @Autowired private DeviceLinkService deviceLinkService;
    @Autowired private FirstSoloOfferService offers;
    @Autowired private PlayerAppService playerAppService;

    private Long profile(int price, int duration, int questions) {
        String name = "Profil " + UUID.randomUUID();
        fraud.createProfile(null, new ProfileRequest(name, price, duration, questions));
        return fraud.profiles().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().id();
    }

    private GameStartResponse playPerfect(User player, Long quizId, String device) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, device);
        GameStartResponse.CurrentQuestion current = game.current();
        while (current != null) {
            Long questionId = current.question().id();
            current = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, data.correctChoice(questionId))).next();
        }
        return game;
    }

    @Test
    void roundingAlwaysGoesAgainstThePlayer() {
        SoloAdjustment adjustment = new SoloAdjustment(50, 80, 30);
        assertThat(adjustment.price(5)).isEqualTo(8);
        assertThat(adjustment.price(0)).isZero();
        assertThat(adjustment.questionSeconds(20)).isEqualTo(4);
        assertThat(adjustment.questionSeconds(3)).isEqualTo(1);
        assertThat(adjustment.questions(3)).isEqualTo(4);
        assertThat(adjustment.harshest(new SoloAdjustment(10, 90, 40)))
                .isEqualTo(new SoloAdjustment(50, 90, 40));
    }

    @Test
    void aSuspectPaysAndPlaysTheHardenedGameButWinsTheSamePrize() {
        Long quizId = data.soloQuiz(data.categoryWithQuestions(40), 4);
        User player = data.player();
        fraud.addSuspect(null, player.getId(), profile(100, 50, 50), "Multi-comptes");

        // Ce que l'app affiche est ce qui est joue et encaisse.
        var shown = quizService.view(quizId).adjustedFor(fraud.adjustmentFor(player.getId()).orElseThrow());
        assertThat(shown.ticketPriceCredits()).isEqualTo(TestData.SOLO_ENTRY * 2);
        assertThat(shown.questionDurationSeconds()).isEqualTo(TestData.QUESTION_SECONDS / 2);
        assertThat(shown.questionsPerGame()).isEqualTo(6);

        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY * 2);
        GameStartResponse game = playPerfect(player, quizId, null);

        assertThat(game.totalQuestions()).isEqualTo(6);
        assertThat(game.questionDurationSeconds()).isEqualTo(TestData.QUESTION_SECONDS / 2);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);
    }

    @Test
    void linkedAccountsGetTheProfileAndRemovalRestoresNormalGames() {
        String device = "web-" + UUID.randomUUID();
        User suspect = data.player();
        User linked = data.player();
        deviceLinkService.seen(suspect.getId(), device);
        deviceLinkService.seen(linked.getId(), device);
        fraud.addSuspect(null, suspect.getId(), profile(0, 0, 100), "Comptes lies");

        assertThat(fraud.adjustmentFor(linked.getId())).contains(new SoloAdjustment(0, 0, 100));
        assertThat(fraud.adjustmentFor(data.player().getId())).isEmpty();

        fraud.removeSuspect(null, suspect.getId());
        assertThat(fraud.adjustmentFor(linked.getId())).isEmpty();
    }

    @Test
    void aSuspectGetsNoFreeFirstGame() {
        playerAppService.update(null, new PlayerAppConfig(null, null, null, null, null, null, true));
        try {
            User player = data.player();
            assertThat(offers.offeredTo(player.getId())).isTrue();
            fraud.addSuspect(null, player.getId(), profile(10, 0, 0), "Suspect");
            assertThat(offers.offeredTo(player.getId())).isFalse();
        } finally {
            playerAppService.update(null, new PlayerAppConfig(null, null, null, null, null, null, false));
        }
    }

    @Test
    void limitsNoteAndProfileDeletionAreEnforced() {
        assertThatThrownBy(() -> fraud.createProfile(null, new ProfileRequest("Trop", 201, 0, 0)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> fraud.createProfile(null, new ProfileRequest("Trop", 0, 81, 0)))
                .isInstanceOf(ApiException.class);
        Long profileId = profile(10, 10, 10);
        User player = data.player();
        assertThatThrownBy(() -> fraud.addSuspect(null, player.getId(), profileId, " "))
                .isInstanceOf(ApiException.class);

        fraud.addSuspect(null, player.getId(), profileId, "Note");
        assertThatThrownBy(() -> fraud.deleteProfile(null, profileId)).isInstanceOf(ApiException.class);
        fraud.removeSuspect(null, player.getId());
        fraud.deleteProfile(null, profileId);
        assertThat(fraud.profiles()).noneMatch(p -> p.id().equals(profileId));
    }
}
