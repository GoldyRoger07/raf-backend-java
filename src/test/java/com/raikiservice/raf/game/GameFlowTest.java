package com.raikiservice.raf.game;

import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameReviewResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.LeaderboardResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.game.web.dto.SubmitGameDto.AnswerDto;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.repository.WalletTransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot 6 : deroule d'une partie, controles anti-triche, cloture, classement et
 * versement des gains.
 */
class GameFlowTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private GameService gameService;
    @Autowired private RankingService rankingService;
    @Autowired private QuizService quizService;
    @Autowired private TicketService ticketService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private GameSessionRepository gameSessionRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;

    /** Partagee entre les tests de la classe : JUnit cree une instance par test. */
    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    // --- Demarrage --------------------------------------------------------------

    @Test
    void startIsRefusedBeforeTheQuizRunsAndWithoutTicket() {
        User player = data.player();
        Long published = data.publishedQuiz(categoryId, 3, 1000, 5, null, null);

        assertApiError(ErrorCode.GAME_NOT_PLAYABLE,
                () -> gameService.start(player.getId(), published, IP, UA));

        User holder = data.player();
        Long running = data.runningQuiz(categoryId, 3, 1000, 1, holder);
        assertApiError(ErrorCode.GAME_NO_TICKET,
                () -> gameService.start(player.getId(), running, IP, UA));
    }

    @Test
    void startSendsTheDrawWithoutAnyCorrectnessHint() {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, player);

        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);

        assertThat(game.resumed()).isFalse();
        assertThat(game.questions()).hasSize(3);
        assertThat(game.questions()).allSatisfy(q -> assertThat(q.choices()).hasSize(4));
        // Le DTO des propositions n'a structurellement pas de champ "correct".
        assertThat(GameStartResponse.ChoiceView.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("id", "text", "imageUrl");
        assertThat(game.remainingMs()).isPositive();
    }

    @Test
    void reloadingResumesTheSameGameWithoutBurningASecondTicket() {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 2, player);

        GameStartResponse first = gameService.start(player.getId(), quizId, IP, UA);
        GameStartResponse again = gameService.start(player.getId(), quizId, IP, UA);

        assertThat(again.resumed()).isTrue();
        assertThat(again.sessionId()).isEqualTo(first.sessionId());
        assertThat(choiceOrder(again)).isEqualTo(choiceOrder(first));
        assertThat(ticketStatuses(player.getId(), quizId))
                .containsExactlyInAnyOrder(TicketStatus.ACTIVE, TicketStatus.USED);
    }

    @Test
    void simultaneousStartsYieldOneGameAndConsumeOneTicket() throws Exception {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 2, player);

        List<GameStartResponse> results = concurrently(4,
                () -> gameService.start(player.getId(), quizId, IP, UA));

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(GameStartResponse::sessionId).containsOnly(
                results.get(0).sessionId());
        assertThat(ticketStatuses(player.getId(), quizId))
                .containsExactlyInAnyOrder(TicketStatus.ACTIVE, TicketStatus.USED);
    }

    // --- Soumission -------------------------------------------------------------

    @Test
    void scoreIsRecomputedByTheServerAndSubmitIsIdempotent() throws Exception {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, player);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(700);

        SubmitGameDto submit = new SubmitGameDto(game.sessionId(), List.of(
                answer(game, 0, true, 200),
                answer(game, 1, false, 450),
                answer(game, 2, true, 700)), 700L);

        GameResultResponse result = gameService.submit(player.getId(), submit);
        assertThat(result.status()).isEqualTo(GameSessionStatus.SUBMITTED);
        assertThat(result.score()).isEqualTo(20);
        assertThat(result.correctCount()).isEqualTo(2);
        assertThat(result.answeredCount()).isEqualTo(3);
        assertThat(result.bestScore()).isEqualTo(20);

        // Rejeu : resultat identique, rien n'est recalcule ni re-horodate.
        GameResultResponse replay = gameService.submit(player.getId(), submit);
        assertThat(replay.score()).isEqualTo(20);
        // DATETIME(6) arrondit a la microseconde : comparaison a la milliseconde pres.
        assertThat(replay.submittedAt()).isCloseTo(result.submittedAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void anotherPlayersGameIsInvisible() {
        User player = data.player();
        User intruder = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, player);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);

        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> gameService.submit(intruder.getId(),
                new SubmitGameDto(game.sessionId(), List.of(), null)));
    }

    @Test
    void answersOutOfOrderInvalidateTheGame() throws Exception {
        assertInvalidated((game, sleepMs) -> List.of(
                answer(game, 1, true, 100),
                answer(game, 0, true, 200)));
    }

    @Test
    void aChoiceFromAnotherQuestionInvalidatesTheGame() throws Exception {
        assertInvalidated((game, sleepMs) -> List.of(new AnswerDto(
                game.questions().get(0).id(),
                game.questions().get(1).choices().get(0).id(),
                100L)));
    }

    @Test
    void decreasingTimesInvalidateTheGame() throws Exception {
        assertInvalidated((game, sleepMs) -> List.of(
                answer(game, 0, true, 500),
                answer(game, 1, true, 300)));
    }

    @Test
    void claimingMoreTimeThanReallyElapsedInvalidatesTheGame() throws Exception {
        assertInvalidated((game, sleepMs) -> List.of(answer(game, 0, true, 30_000)));
    }

    @Test
    void humanlyImpossibleReactionTimesAreFlaggedButNotPunished() throws Exception {
        Long bigCategory = data.categoryWithQuestions(50);
        User player = data.player();
        Long quizId = data.runningQuiz(bigCategory, 5, 1000, 1, player);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(100);

        List<AnswerDto> answers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            answers.add(answer(game, i, true, 10L * (i + 1)));
        }
        GameResultResponse result = gameService.submit(player.getId(),
                new SubmitGameDto(game.sessionId(), answers, null));

        assertThat(result.status()).isEqualTo(GameSessionStatus.SUBMITTED);
        assertThat(result.score()).isEqualTo(50);
        assertThat(gameSessionRepository.findById(game.sessionId()).orElseThrow().isFlagged()).isTrue();
    }

    // --- Cloture et classement ---------------------------------------------------

    @Test
    void firstScoreAboveThresholdStartsTheClosingCountdown() throws Exception {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 15, 1, player);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(400);

        gameService.submit(player.getId(), new SubmitGameDto(game.sessionId(), List.of(
                answer(game, 0, true, 100), answer(game, 1, true, 200)), null));

        var quiz = quizRepository.findById(quizId).orElseThrow();
        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.CLOSING);
        assertThat(quiz.getEndsAt()).isNotNull();
    }

    @Test
    void finishingRanksPlayersAndPaysPrizesExactlyOnce() throws Exception {
        User first = data.player();
        User second = data.player();
        User third = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 2, first, second, third);

        play(first, quizId, 3);
        play(second, quizId, 1);
        play(second, quizId, 2);          // son meilleur score, 20, est retenu
        play(third, quizId, 2);           // meme score que second, mais a demarre apres lui

        // Les resultats ne sont pas publics avant la fin.
        Long firstGame = gameSessionRepository
                .findByQuizIdAndUserIdOrderByStartedAtDesc(quizId, first.getId()).get(0).getId();
        assertApiError(ErrorCode.GAME_RESULTS_NOT_READY,
                () -> rankingService.leaderboard(quizId, first.getId(), PageRequest.of(0, 10)));
        assertApiError(ErrorCode.GAME_REVIEW_LOCKED,
                () -> gameService.review(first.getId(), firstGame, false));

        close(quizId);
        assertThat(rankingService.finalizeQuiz(null, quizId)).isTrue();
        assertThat(rankingService.finalizeQuiz(null, quizId)).isFalse();

        LeaderboardResponse board = rankingService.leaderboard(quizId, second.getId(),
                PageRequest.of(0, 10));
        assertThat(board.totalRanked()).isEqualTo(3);
        assertThat(board.entries()).extracting(LeaderboardResponse.Entry::score)
                .containsExactly(30, 20, 20);
        assertThat(board.entries()).extracting(LeaderboardResponse.Entry::rewardCredits)
                .containsExactly(TestData.FIRST_PRIZE, TestData.SECOND_PRIZE, 0L);
        assertThat(board.me().rank()).isEqualTo(2);
        assertThat(board.entries().get(0).displayName()).endsWith(" T.");

        assertThat(data.balance(first.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.FIRST_PRIZE);
        assertThat(data.balance(second.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SECOND_PRIZE);
        assertThat(walletTransactionRepository.findAll()).filteredOn(t ->
                t.getType() == WalletTransactionType.PRIZE
                        && t.getIdempotencyKey().startsWith("prize:quiz:" + quizId + ":"))
                .hasSize(2);

        GameReviewResponse review = gameService.review(first.getId(), firstGame, false);
        assertThat(review.items()).hasSize(3).allSatisfy(item -> assertThat(item.correct()).isTrue());
    }

    @Test
    void rankingWaitsForGamesStillInProgress() throws Exception {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, player);
        gameService.start(player.getId(), quizId, IP, UA);

        close(quizId);
        assertThat(rankingService.finalizeQuiz(null, quizId)).isFalse();
        assertApiError(ErrorCode.GAME_STILL_RUNNING,
                () -> rankingService.finalizeByAdmin(null, quizId));
        assertThat(quizRepository.findById(quizId).orElseThrow().getStatus())
                .isEqualTo(QuizStatus.COMPUTING);
    }

    @Test
    void anAdminCanInvalidateUntilPrizesArePaid() throws Exception {
        User cheater = data.player();
        User honest = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, cheater, honest);
        Long cheaterGame = play(cheater, quizId, 3);
        play(honest, quizId, 1);

        gameService.invalidateByAdmin(null, cheaterGame, "partage des reponses");
        close(quizId);
        rankingService.finalizeQuiz(null, quizId);

        LeaderboardResponse board = rankingService.leaderboard(quizId, honest.getId(),
                PageRequest.of(0, 10));
        assertThat(board.totalRanked()).isEqualTo(1);
        assertThat(board.me().rank()).isEqualTo(1);
        assertThat(data.balance(honest.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.FIRST_PRIZE);

        Long honestGame = gameSessionRepository
                .findByQuizIdAndUserIdOrderByStartedAtDesc(quizId, honest.getId()).get(0).getId();
        assertApiError(ErrorCode.QUIZ_INVALID_STATE,
                () -> gameService.invalidateByAdmin(null, honestGame, "trop tard"));
    }

    @Test
    void servedAndCorrectCountersAreRecordedAfterCommit() throws Exception {
        Long freshCategory = data.categoryWithQuestions(30);
        User player = data.player();
        Long quizId = data.runningQuiz(freshCategory, 3, 1000, 1, player);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(300);
        gameService.submit(player.getId(), new SubmitGameDto(game.sessionId(), List.of(
                answer(game, 0, true, 100), answer(game, 1, false, 200)), null));

        List<Long> ids = game.questions().stream().map(GameStartResponse.QuestionView::id).toList();
        var questions = questionRepository.findAllById(ids);
        assertThat(questions).extracting(q -> q.getTimesServed()).containsOnly(1);
        assertThat(questions.stream().mapToInt(q -> q.getTimesCorrect()).sum()).isEqualTo(1);
    }

    // --- Outils -------------------------------------------------------------------

    /** Joue une partie complete avec {@code correct} bonnes reponses sur 3. */
    private Long play(User player, Long quizId, int correct) throws Exception {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(400);
        List<AnswerDto> answers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            answers.add(answer(game, i, i < correct, 100L * (i + 1)));
        }
        GameResultResponse result = gameService.submit(player.getId(),
                new SubmitGameDto(game.sessionId(), answers, null));
        assertThat(result.status()).isEqualTo(GameSessionStatus.SUBMITTED);
        return game.sessionId();
    }

    /** Ce que ferait l'ordonnanceur une fois ends_at depasse. */
    private void close(Long quizId) {
        var quiz = quizRepository.findById(quizId).orElseThrow();
        if (quiz.getStatus() == QuizStatus.RUNNING) {
            quizService.triggerClosing(quizId, quiz.getMinScoreThreshold() + 1);
        }
        quizService.moveToComputing(quizId);
    }

    private AnswerDto answer(GameStartResponse game, int position, boolean correct, long elapsedMs) {
        Long questionId = game.questions().get(position).id();
        return new AnswerDto(questionId,
                correct ? data.correctChoice(questionId) : data.wrongChoice(questionId), elapsedMs);
    }

    private interface Answers {
        List<AnswerDto> build(GameStartResponse game, long sleepMs);
    }

    private void assertInvalidated(Answers answers) throws Exception {
        User player = data.player();
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, player);
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        Thread.sleep(600);

        GameResultResponse result = gameService.submit(player.getId(),
                new SubmitGameDto(game.sessionId(), answers.build(game, 600), null));

        assertThat(result.status()).isEqualTo(GameSessionStatus.INVALIDATED);
        assertThat(result.score()).isZero();
        // L'invalidation est bien commitee : un nouvel envoi ne repasse pas les controles.
        GameResultResponse retry = gameService.submit(player.getId(), new SubmitGameDto(
                game.sessionId(), List.of(answer(game, 0, true, 100)), null));
        assertThat(retry.status()).isEqualTo(GameSessionStatus.INVALIDATED);
    }

    private List<Long> choiceOrder(GameStartResponse game) {
        return game.questions().stream()
                .flatMap(q -> q.choices().stream())
                .map(GameStartResponse.ChoiceView::id)
                .toList();
    }

    private List<TicketStatus> ticketStatuses(Long userId, Long quizId) {
        return ticketService.myTicketsForQuiz(userId, quizId).stream()
                .map(t -> t.status())
                .toList();
    }
}
