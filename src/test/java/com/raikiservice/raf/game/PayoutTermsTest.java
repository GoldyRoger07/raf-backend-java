package com.raikiservice.raf.game;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.PayoutTermsService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.service.PayoutSpecialCaseService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accord du paiement des retraits par tranches : demande avant le solo aux seuls
 * joueurs de la liste des cas de retrait particuliers.
 */
class PayoutTermsTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private GameService gameService;
    @Autowired private PayoutTermsService payoutTermsService;
    @Autowired private PayoutSpecialCaseService specialCases;
    @Autowired private UserRepository userRepository;

    private Long freeQuiz() {
        return data.soloQuiz(data.categoryWithQuestions(30), 3, 0);
    }

    private void play(User player, Long quizId, boolean perfect) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, null);
        GameStartResponse.CurrentQuestion current = game.current();
        while (current != null) {
            Long questionId = current.question().id();
            Long choice = perfect ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            current = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(questionId, choice))
                    .next();
        }
    }

    @Test
    void aPlayerOutsideTheListNeverSeesTheAgreementEvenAfterAPerfectGame() {
        User player = data.player();
        play(player, freeQuiz(), true);

        assertThat(payoutTermsService.status(player.getId()).required()).isFalse();
        play(player, freeQuiz(), false);
    }

    @Test
    void aListedPlayerMustAcceptBeforeTheNextSoloGame() {
        User player = data.player();
        Long quizId = freeQuiz();
        specialCases.add(data.admin().getId(), player.getId(), "gros gain");

        // Dans la liste : l'accord est demande, sans-faute ou non.
        assertThat(payoutTermsService.status(player.getId()))
                .isEqualTo(new PayoutTermsService.Status(true, PayoutTermsService.CURRENT_VERSION));
        assertApiError(ErrorCode.SOLO_PAYOUT_TERMS_REQUIRED,
                () -> gameService.start(player.getId(), quizId, IP, UA, null));
        assertApiError(ErrorCode.PAYOUT_TERMS_OUTDATED,
                () -> payoutTermsService.accept(player.getId(), PayoutTermsService.CURRENT_VERSION - 1));

        payoutTermsService.accept(player.getId(), PayoutTermsService.CURRENT_VERSION);

        assertThat(payoutTermsService.status(player.getId()).required()).isFalse();
        assertThat(userRepository.findById(player.getId()).orElseThrow().getPayoutTermsAcceptedAt()).isNotNull();
        gameService.start(player.getId(), quizId, IP, UA, null);
    }

    @Test
    void removingAPlayerLiftsTheRequirementAndKeepsHisAgreement() {
        User player = data.player();
        Long admin = data.admin().getId();
        specialCases.add(admin, player.getId(), null);
        assertThat(payoutTermsService.status(player.getId()).required()).isTrue();

        specialCases.remove(admin, player.getId());
        assertThat(payoutTermsService.status(player.getId()).required()).isFalse();
        play(player, freeQuiz(), false);

        // Accepte, retire puis remis : pas besoin de reaccepter.
        specialCases.add(admin, player.getId(), null);
        payoutTermsService.accept(player.getId(), PayoutTermsService.CURRENT_VERSION);
        specialCases.remove(admin, player.getId());
        specialCases.add(admin, player.getId(), "de retour");
        assertThat(payoutTermsService.status(player.getId()).required()).isFalse();
        assertThat(specialCases.list()).filteredOn(c -> c.playerId().equals(player.getId()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.note()).isEqualTo("de retour");
                    assertThat(c.termsAcceptedAt()).isNotNull();
                });
    }

    @Test
    void listedTestAccountsAreAskedToo() {
        User tester = data.player();
        tester.setTest(true);
        userRepository.save(tester);
        specialCases.add(data.admin().getId(), tester.getId(), null);

        assertApiError(ErrorCode.SOLO_PAYOUT_TERMS_REQUIRED,
                () -> gameService.start(tester.getId(), freeQuiz(), IP, UA, null));
    }
}
