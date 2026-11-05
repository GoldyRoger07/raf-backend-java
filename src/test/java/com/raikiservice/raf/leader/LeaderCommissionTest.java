package com.raikiservice.raf.leader;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.game.web.dto.SubmitGameDto.AnswerDto;
import com.raikiservice.raf.leader.service.LeaderService;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderCommission;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderSummary;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.repository.WalletTransactionRepository;
import com.raikiservice.raf.wallet.service.WalletService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Leaders : versement des commissions et regles de gestion. */
class LeaderCommissionTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private LeaderService leaderService;
    @Autowired private GameService gameService;
    @Autowired private RankingService rankingService;
    @Autowired private QuizService quizService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private WalletService walletService;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbc;

    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    // --- Versement ----------------------------------------------------------------

    @Test
    void aGroupQuizPaysTheLeaderOnAllTicketsOfPlayersWhoWonNothing() throws Exception {
        User leader = leader("10");
        User winner = data.player();
        User runnerUp = data.player();
        User loser = data.player();
        User neverPlayed = data.player();
        for (User player : List.of(winner, loser, neverPlayed)) {
            leaderService.addPlayer(null, leader.getId(), player.getId());
        }
        // Deux tickets chacun ; le 1er et le 2e sont payes (voir TestData).
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 2, winner, runnerUp, loser, neverPlayed);
        play(winner, quizId, 3);
        play(runnerUp, quizId, 2);
        play(loser, quizId, 1);

        close(quizId);
        assertThat(rankingService.finalizeQuiz(null, quizId)).isTrue();

        // loser et neverPlayed : 2 tickets x 10 credits x taux x 10 %, chacun.
        long rate = walletService.htgRate();
        long each = 2L * TestData.TICKET_PRICE * rate / 10;
        assertThat(data.balance(leader.getId(), BalanceType.WINNINGS)).isEqualTo(2 * each);
        // Un seul mouvement pour le leader sur ce quiz, detaille par joueur.
        assertThat(walletTransactionRepository.findAll()).filteredOn(t ->
                t.getType() == WalletTransactionType.COMMISSION
                        && t.getIdempotencyKey().startsWith("commission:quiz:" + quizId + ":"))
                .hasSize(1);
        List<LeaderCommission> lines = leaderService.commissions(leader.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(lines).extracting(LeaderCommission::playerId)
                .containsExactlyInAnyOrder(loser.getId(), neverPlayed.getId());
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.source()).isEqualTo("QUIZ");
            assertThat(line.baseCredits()).isEqualTo(2L * TestData.TICKET_PRICE);
            assertThat(line.amountHtg()).isEqualTo(each);
        });

        LeaderSummary summary = leaderService.get(leader.getId());
        assertThat(summary.playerCount()).isEqualTo(3);
        assertThat(summary.commissionsTotalHtg()).isEqualTo(2 * each);
        assertThat(summary.commissionsLast30DaysHtg()).isEqualTo(2 * each);

        // Chaque mouvement est rattache a son detail : rien a signaler.
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> leader.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void aLostSoloGamePaysTheLeaderRoundedDownAndAPerfectGameDoesNot() {
        User leader = leader("10");
        User player = data.player();
        leaderService.addPlayer(null, leader.getId(), player.getId());
        data.credit(player.getId(), BalanceType.CREDIT, 2L * TestData.SOLO_ENTRY);
        Long lost = data.soloQuiz(categoryId, 3);
        Long won = data.soloQuiz(data.categoryWithQuestions(30), 3);

        assertThat(playSolo(player, lost, 2).won()).isFalse();
        long expected = TestData.SOLO_ENTRY * walletService.htgRate() / 10;   // arrondi inferieur
        assertThat(data.balance(leader.getId(), BalanceType.WINNINGS)).isEqualTo(expected);

        assertThat(playSolo(player, won, 3).won()).isTrue();
        assertThat(data.balance(leader.getId(), BalanceType.WINNINGS)).isEqualTo(expected);
        assertThat(leaderService.commissions(leader.getId(), PageRequest.of(0, 10)).getContent())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.source()).isEqualTo("SOLO");
                    assertThat(line.commissionPercent()).isEqualByComparingTo("10");
                });
    }

    @Test
    void aFormerLeaderTouchesNothingAndHisPlayersAreFreed() {
        User leader = leader("50");
        User player = data.player();
        leaderService.addPlayer(null, leader.getId(), player.getId());
        leaderService.demote(null, leader.getId());

        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        playSolo(player, data.soloQuiz(categoryId, 3), 0);
        assertThat(winnings(leader)).isZero();

        // Libre : il peut rejoindre un autre leader.
        User other = leader("5");
        leaderService.addPlayer(null, other.getId(), player.getId());
        assertThat(leaderService.players(other.getId(), PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(p -> assertThat(p.id()).isEqualTo(player.getId()));
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> leaderService.get(leader.getId()));
    }

    @Test
    void aZeroPercentLeaderTouchesNothing() {
        User leader = leader("0");
        User player = data.player();
        leaderService.addPlayer(null, leader.getId(), player.getId());
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        playSolo(player, data.soloQuiz(categoryId, 3), 0);
        assertThat(winnings(leader)).isZero();
    }

    // --- Regles de gestion --------------------------------------------------------

    @Test
    void aPlayerHasOneLeaderAndALeaderIsNobodysPlayer() {
        User first = leader("10");
        User second = leader("10");
        User player = data.player();
        leaderService.addPlayer(null, first.getId(), player.getId());

        assertApiError(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                () -> leaderService.addPlayer(null, second.getId(), player.getId()));
        assertApiError(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                () -> leaderService.addPlayer(null, first.getId(), second.getId()));
        assertApiError(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                () -> leaderService.addPlayer(null, first.getId(), first.getId()));
        assertApiError(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                () -> leaderService.addPlayer(null, first.getId(), data.admin().getId()));
        // Un joueur rattache ne devient pas leader sans etre d'abord detache.
        assertApiError(ErrorCode.LEADER_NOT_ELIGIBLE,
                () -> leaderService.promote(null, player.getId(), BigDecimal.ONE));

        leaderService.removePlayer(null, first.getId(), player.getId());
        leaderService.addPlayer(null, second.getId(), player.getId());
        assertThat(leaderService.get(first.getId()).playerCount()).isZero();
        assertThat(leaderService.get(second.getId()).playerCount()).isEqualTo(1);
    }

    @Test
    void theCommissionIsAPercentageWithTwoDecimalsAtMost() {
        User leader = leader("10");
        assertThat(leaderService.setCommission(null, leader.getId(), new BigDecimal("2.5"))
                .commissionPercent()).isEqualByComparingTo("2.50");
        for (String invalid : List.of("-1", "100.01", "1.234")) {
            assertApiError(ErrorCode.LEADER_COMMISSION_INVALID,
                    () -> leaderService.setCommission(null, leader.getId(), new BigDecimal(invalid)));
        }
        assertApiError(ErrorCode.LEADER_NOT_ELIGIBLE,
                () -> leaderService.promote(null, data.admin().getId(), BigDecimal.ONE));
        assertApiError(ErrorCode.LEADER_NOT_ELIGIBLE,
                () -> leaderService.promote(null, leader.getId(), BigDecimal.ONE));
    }

    // --- Outils -------------------------------------------------------------------

    /** Solde des gains, 0 si le portefeuille n'existe pas encore (rien n'a ete verse). */
    private long winnings(User user) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(winnings_balance), 0) FROM wallets WHERE user_id = ?",
                Long.class, user.getId());
        return value == null ? 0 : value;
    }

    private User leader(String percent) {
        User user = data.player();
        leaderService.promote(null, user.getId(), new BigDecimal(percent));
        return user;
    }

    private void play(User player, Long quizId, int correct) throws Exception {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(400);
        List<AnswerDto> answers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long questionId = game.questions().get(i).id();
            answers.add(new AnswerDto(questionId,
                    i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId),
                    100L * (i + 1)));
        }
        gameService.submit(player.getId(), new SubmitGameDto(game.sessionId(), answers, null));
    }

    private void close(Long quizId) {
        var quiz = quizRepository.findById(quizId).orElseThrow();
        if (quiz.getStatus() == QuizStatus.RUNNING) {
            quizService.triggerClosing(quizId, quiz.getMinScoreThreshold() + 1);
        }
        quizService.moveToComputing(quizId);
    }

    private GameResultResponse playSolo(User player, Long quizId, int correct) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        GameStartResponse.CurrentQuestion current = game.current();
        GameTurnResponse turn = null;
        for (int i = 0; current != null; i++) {
            Long questionId = current.question().id();
            Long choiceId = i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            turn = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(questionId, choiceId));
            current = turn.next();
        }
        return turn.result();
    }
}
