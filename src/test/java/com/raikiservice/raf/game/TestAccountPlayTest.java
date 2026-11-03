package com.raikiservice.raf.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.web.dto.SubmitGameDto.AnswerDto;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.LeaderboardResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.repository.WalletRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compte de test : joue sans payer et sans limite, ne gagne rien, et sur un vrai
 * quiz ne compte ni pour le seuil, ni pour la cloture, ni pour le classement.
 */
class TestAccountPlayTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private TicketService ticketService;
    @Autowired private QuizService quizService;
    @Autowired private GameService gameService;
    @Autowired private RankingService rankingService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;

    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    @Test
    void onARealQuizATesterPlaysForFreeWithoutTakingAnyonesPlace() throws Exception {
        User tester = tester();
        User player = data.player();
        // Un joueur reel suffit a demarrer, un ticket par joueur au plus.
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1, null, 1);

        for (int i = 0; i < 3; i++) {
            ticketService.purchase(tester.getId(), quizId, UUID.randomUUID().toString());
        }
        assertThat(balance(tester.getId(), BalanceType.CREDIT)).isZero();
        // Ses tickets ne declenchent pas le demarrage d'un vrai quiz.
        assertThat(quizRepository.findById(quizId).orElseThrow().getStatus()).isEqualTo(QuizStatus.REGISTRATION);
        assertThat(quizService.view(quizId).playersRegistered()).isZero();

        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());
        quizService.start(null, quizId);

        play(tester, quizId, 3);
        play(player, quizId, 1);
        close(quizId);
        rankingService.finalizeQuiz(null, quizId);

        LeaderboardResponse board = rankingService.leaderboard(quizId, player.getId(), PageRequest.of(0, 10));
        assertThat(board.totalRanked()).isEqualTo(1);
        assertThat(board.me().rank()).isEqualTo(1);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.FIRST_PRIZE);
        assertThat(balance(tester.getId(), BalanceType.WINNINGS)).isZero();
    }

    @Test
    void aTesterDoesNotCloseARealQuiz() throws Exception {
        User tester = tester();
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 15, 1, player);
        ticketService.purchase(tester.getId(), quizId, UUID.randomUUID().toString());

        play(tester, quizId, 3);

        assertThat(quizRepository.findById(quizId).orElseThrow().getStatus()).isEqualTo(QuizStatus.RUNNING);
    }

    @Test
    void onATestQuizTestersStartRankAndWinNothing() throws Exception {
        User first = tester();
        User second = tester();
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 2, null, null);
        quizService.setTest(null, quizId, true);

        ticketService.purchase(first.getId(), quizId, UUID.randomUUID().toString());
        ticketService.purchase(second.getId(), quizId, UUID.randomUUID().toString());
        // Sur un quiz de test, les testeurs comptent : le quiz se programme.
        assertThat(quizRepository.findById(quizId).orElseThrow().getStatus()).isEqualTo(QuizStatus.SCHEDULED);
        quizService.start(null, quizId);

        play(first, quizId, 3);
        play(second, quizId, 1);
        close(quizId);
        rankingService.finalizeQuiz(null, quizId);

        LeaderboardResponse board = rankingService.leaderboard(quizId, first.getId(), PageRequest.of(0, 10));
        assertThat(board.totalRanked()).isEqualTo(2);
        assertThat(board.entries()).extracting(LeaderboardResponse.Entry::rewardHtg).containsOnly(0L);
        assertThat(balance(first.getId(), BalanceType.WINNINGS)).isZero();
    }

    @Test
    void aTesterPlaysSoloForFreeAsOftenAsHeWantsAndWinsNothing() {
        User tester = tester();
        Long quizId = data.soloQuiz(categoryId, 3);

        for (int i = 0; i < 2; i++) {
            GameStartResponse game = gameService.start(tester.getId(), quizId, IP, UA);
            GameStartResponse.CurrentQuestion current = game.current();
            while (current != null) {
                Long questionId = current.question().id();
                current = gameService.answer(tester.getId(), game.sessionId(),
                        new AnswerQuestionDto(questionId,
                                data.correctChoice(questionId))).next();
            }
        }

        assertThat(balance(tester.getId(), BalanceType.CREDIT)).isZero();
        assertThat(balance(tester.getId(), BalanceType.WINNINGS)).isZero();
        assertThat(gameService.soloSubjectsPlayedToday(tester.getId())).isEmpty();
    }

    /** Un testeur n'a parfois aucun portefeuille : rien n'a jamais ete credite ni debite. */
    private long balance(Long userId, BalanceType type) {
        return walletRepository.findByUserId(userId).map(wallet -> wallet.balanceOf(type)).orElse(0L);
    }

    private User tester() {
        User user = data.player();
        user.setTest(true);
        return userRepository.save(user);
    }

    private void play(User player, Long quizId, int correct) throws Exception {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(400);
        List<AnswerDto> answers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long questionId = game.questions().get(i).id();
            answers.add(new AnswerDto(questionId,
                    i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId), 100L * (i + 1)));
        }
        GameResultResponse result = gameService.submit(player.getId(), new SubmitGameDto(game.sessionId(), answers, null));
        assertThat(result.status()).isEqualTo(GameSessionStatus.SUBMITTED);
    }

    private void close(Long quizId) {
        var quiz = quizRepository.findById(quizId).orElseThrow();
        if (quiz.getStatus() == QuizStatus.RUNNING) {
            quizService.triggerClosing(quizId, quiz.getMinScoreThreshold() + 1);
        }
        quizService.moveToComputing(quizId);
    }
}
