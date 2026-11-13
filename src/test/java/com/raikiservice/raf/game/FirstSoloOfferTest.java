package com.raikiservice.raf.game;

import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.PlayerAppService.PlayerAppConfig;
import com.raikiservice.raf.game.service.FirstSoloOfferService;
import com.raikiservice.raf.game.service.FirstSoloOfferService.OfferReason;
import com.raikiservice.raf.game.service.FirstSoloOfferService.OfferStatus;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
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

/** Premiere partie solo offerte : interrupteur App joueur, une fois par compte et par groupe lie. */
class FirstSoloOfferTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private PlayerAppService playerAppService;
    @Autowired private FirstSoloOfferService offers;
    @Autowired private GameService gameService;
    @Autowired private DeviceLinkService deviceLinkService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    /** Reglage global, partage par toute la suite : toujours recoupe. */
    @AfterEach
    void switchOff() {
        setEnabled(false);
    }

    private void setEnabled(boolean enabled) {
        playerAppService.update(null, new PlayerAppConfig(null, null, null, null, null, null, enabled));
    }

    private Long paidQuiz() {
        return data.soloQuiz(data.categoryWithQuestions(30), 3);
    }

    private GameStartResponse play(User player, Long quizId, boolean perfect, String device) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA, device);
        GameStartResponse.CurrentQuestion current = game.current();
        while (current != null) {
            Long questionId = current.question().id();
            Long choice = perfect ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            current = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(questionId, choice))
                    .next();
        }
        return game;
    }

    private int entryPrice(Long sessionId) {
        return jdbc.queryForObject("SELECT entry_price_credits FROM game_sessions WHERE id = ?", Integer.class, sessionId);
    }

    @Test
    void switchedOffByDefaultTheFirstGameIsPaid() {
        assertThat(playerAppService.get().firstSoloFreeEnabled()).isFalse();
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);

        assertThat(offers.offeredTo(player.getId())).isFalse();
        play(player, paidQuiz(), false, null);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
    }

    @Test
    void theFirstPaidGameIsFreeOnceAndAPerfectPaysTheFullPrize() {
        setEnabled(true);
        User player = data.player();
        // Un quiz deja gratuit ne consomme pas l'offre.
        play(player, data.soloQuiz(data.categoryWithQuestions(30), 3, 0), false, null);
        assertThat(offers.offeredTo(player.getId())).isTrue();

        // Sans aucun credit : la partie payante est offerte, et le sans-faute paie le prix.
        GameStartResponse game = play(player, paidQuiz(), true, null);
        assertThat(entryPrice(game.sessionId())).isZero();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);
        assertThat(jdbc.queryForObject("SELECT session_id FROM solo_free_offers WHERE user_id = ?", Long.class,
                player.getId())).isEqualTo(game.sessionId());

        // Une seule fois : la partie suivante est payee.
        assertThat(offers.offeredTo(player.getId())).isFalse();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        GameStartResponse paid = play(player, paidQuiz(), false, null);
        assertThat(entryPrice(paid.sessionId())).isEqualTo(TestData.SOLO_ENTRY);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();

        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void linkedAccountsShareASingleOffer() {
        setEnabled(true);
        String device = "web-" + UUID.randomUUID();
        User first = data.player();
        User second = data.player();
        deviceLinkService.seen(second.getId(), device);

        play(first, paidQuiz(), false, device);

        assertThat(offers.offeredTo(second.getId())).isFalse();
        assertThat(offers.offeredTo(data.player().getId())).isTrue();
    }

    @Test
    void playersWhoAlreadyPaidAndTestAccountsGetNothing() {
        User veteran = data.player();
        data.credit(veteran.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        play(veteran, paidQuiz(), false, null);
        setEnabled(true);
        assertThat(offers.offeredTo(veteran.getId())).isFalse();

        User tester = data.player();
        tester.setTest(true);
        userRepository.save(tester);
        assertThat(offers.offeredTo(tester.getId())).isFalse();
    }

    @Test
    void theBackOfficeSeesWhyAPlayerHasNoOffer() {
        User newcomer = data.player();
        assertThat(offers.explain(newcomer.getId()).reason()).isEqualTo(OfferReason.DISABLED);

        setEnabled(true);
        assertThat(offers.explain(newcomer.getId()).eligible()).isTrue();

        String device = "web-" + UUID.randomUUID();
        User sibling = data.player();
        deviceLinkService.seen(sibling.getId(), device);
        GameStartResponse game = play(newcomer, paidQuiz(), false, device);

        OfferStatus received = offers.explain(newcomer.getId());
        assertThat(received.reason()).isEqualTo(OfferReason.RECEIVED);
        assertThat(received.sessionId()).isEqualTo(game.sessionId());

        OfferStatus linked = offers.explain(sibling.getId());
        assertThat(linked.eligible()).isFalse();
        assertThat(linked.reason()).isEqualTo(OfferReason.LINKED_RECEIVED);
        assertThat(linked.linkedPlayerId()).isEqualTo(newcomer.getId());
    }
}
