package com.raikiservice.raf.game;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.question.web.dto.CategoryResponse;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.RewardService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.quiz.web.dto.SoloQuizDto;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mode Solo : partie payee au lancement, prix pour un sans-faute, une partie par
 * sujet et par jour, disponibilite des sujets par mode de jeu.
 */
class SoloGameTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private GameService gameService;
    @Autowired private QuizService quizService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private TicketService ticketService;
    @Autowired private RewardService rewardService;
    @Autowired private ReconciliationService reconciliationService;

    /** Partagee entre les tests de la classe : JUnit cree une instance par test. */
    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    // --- Partie -------------------------------------------------------------------

    @Test
    void aPerfectGameWinsThePrize() throws Exception {
        User player = funded(1);
        Long quizId = data.soloQuiz(categoryId, 3);

        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        assertThat(game.playMode()).isEqualTo(PlayMode.SEQUENTIAL);
        assertThat(game.questions()).isEmpty();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();

        GameResultResponse result = play(player, game, 3);

        assertThat(result.gameMode()).isEqualTo(GameMode.SOLO);
        assertThat(result.correctCount()).isEqualTo(3);
        assertThat(result.won()).isTrue();
        assertThat(result.prizeHtg()).isEqualTo(TestData.SOLO_PRIZE);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);

        // Le joueur est prevenu par email (gain = argent), apres le commit.
        eventually(() -> assertThat(mails.to(player.getEmail())).anySatisfy(mail ->
                assertThat(mail.subject()).startsWith("Sans-faute")));

        // Debit d'entree et prix sont rattaches a la partie : rien a signaler.
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void oneMistakeWinsNothing() {
        User player = funded(1);
        Long quizId = data.soloQuiz(categoryId, 3);

        GameResultResponse result = play(player, gameService.start(player.getId(), quizId, IP, UA), 2);

        assertThat(result.correctCount()).isEqualTo(2);
        assertThat(result.won()).isFalse();
        assertThat(result.prizeHtg()).isNull();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isZero();
    }

    @Test
    void aFreeGameCostsNothingStillPaysThePrizeAndStaysOncePerDay() {
        User player = data.player();
        Long quizId = data.soloQuiz(categoryId, 3, 0);

        // Aucun credit : la partie gratuite se lance quand meme.
        GameResultResponse result = play(player, gameService.start(player.getId(), quizId, IP, UA), 3);

        assertThat(result.won()).isTrue();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(TestData.SOLO_PRIZE);
        assertApiError(ErrorCode.SOLO_ALREADY_PLAYED_TODAY,
                () -> gameService.start(player.getId(), quizId, IP, UA));
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void notEnoughCreditsRefusesTheGame() {
        User player = data.player();
        Long quizId = data.soloQuiz(categoryId, 3);

        assertApiError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> gameService.start(player.getId(), quizId, IP, UA));
        assertThat(gameService.soloSubjectsPlayedToday(player.getId())).isEmpty();
    }

    // --- Une partie par sujet et par jour ----------------------------------------

    @Test
    void eachSubjectIsPlayableOncePerDay() {
        Long otherCategory = data.categoryWithQuestions(30);
        User player = funded(3);
        Long quizId = data.soloQuiz(categoryId, 3);
        Long sameSubject = data.soloQuiz(categoryId, 3);
        Long otherSubject = data.soloQuiz(otherCategory, 3);

        play(player, gameService.start(player.getId(), quizId, IP, UA), 1);

        assertApiError(ErrorCode.SOLO_ALREADY_PLAYED_TODAY,
                () -> gameService.start(player.getId(), quizId, IP, UA));
        // La limite porte sur le sujet, pas sur le quiz.
        assertApiError(ErrorCode.SOLO_ALREADY_PLAYED_TODAY,
                () -> gameService.start(player.getId(), sameSubject, IP, UA));

        gameService.start(player.getId(), otherSubject, IP, UA);

        assertThat(gameService.soloSubjectsPlayedToday(player.getId()))
                .containsExactlyInAnyOrder(categoryId, otherCategory);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(TestData.SOLO_ENTRY);
    }

    @Test
    void reloadingResumesTheGameWithoutPayingTwice() {
        User player = funded(2);
        Long quizId = data.soloQuiz(categoryId, 3);

        GameStartResponse first = gameService.start(player.getId(), quizId, IP, UA);
        GameStartResponse again = gameService.start(player.getId(), quizId, IP, UA);

        assertThat(again.resumed()).isTrue();
        assertThat(again.sessionId()).isEqualTo(first.sessionId());
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(TestData.SOLO_ENTRY);
    }

    @Test
    void simultaneousStartsPayOnce() throws Exception {
        User player = funded(4);
        Long quizId = data.soloQuiz(categoryId, 3);

        List<GameStartResponse> results = concurrently(4,
                () -> gameService.start(player.getId(), quizId, IP, UA));

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(GameStartResponse::sessionId)
                .containsOnly(results.get(0).sessionId());
        assertThat(data.balance(player.getId(), BalanceType.CREDIT))
                .isEqualTo(3L * TestData.SOLO_ENTRY);
    }

    // --- Sujets et cycle de vie -----------------------------------------------------

    @Test
    void subjectsCanBeSwitchedOffPerMode() {
        Long subject = data.categoryWithQuestions(30);
        Long quizId = data.soloQuiz(subject, 3);

        categoryService.configureModes(null, subject, false, null);
        assertApiError(ErrorCode.CATEGORY_MODE_DISABLED,
                () -> data.publishedQuiz(subject, 3, 1000, 1, null, null));
        assertThat(categoryService.listActive(GameMode.SOLO))
                .extracting(CategoryResponse::id).contains(subject);

        categoryService.configureModes(null, subject, true, false);
        assertApiError(ErrorCode.CATEGORY_MODE_DISABLED, () -> data.soloQuiz(subject, 3));
        assertThat(categoryService.listActive(GameMode.SOLO))
                .extracting(CategoryResponse::id).doesNotContain(subject);

        // Un quiz solo deja ouvert n'accepte plus de partie sur ce sujet.
        User player = funded(1);
        assertApiError(ErrorCode.GAME_NOT_PLAYABLE,
                () -> gameService.start(player.getId(), quizId, IP, UA));
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(TestData.SOLO_ENTRY);
    }

    @Test
    void aSoloQuizHasNoTicketsNorRankingAndCanBeClosed() {
        User player = funded(1);
        Long quizId = data.soloQuiz(categoryId, 3);
        var quiz = quizRepository.findById(quizId).orElseThrow();

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.RUNNING);
        assertThat(quizService.poolSize(quizId)).isEqualTo(30);
        assertApiError(ErrorCode.QUIZ_TICKETS_CLOSED, () -> ticketService.purchase(
                player.getId(), quizId, UUID.randomUUID().toString()));
        assertApiError(ErrorCode.QUIZ_INVALID_STATE, () -> rewardService.configure(null, quiz,
                List.of(new RewardTierDto(1, 1, 100, null))));
        assertThat(quizService.viewVisible(GameMode.SOLO, PageRequest.of(0, 100)).getContent())
                .extracting(QuizResponse::id).contains(quizId);
        assertThat(quizService.viewVisible(GameMode.GROUP, PageRequest.of(0, 100)).getContent())
                .extracting(QuizResponse::id).doesNotContain(quizId);

        quizService.closeSolo(null, quizId);

        assertApiError(ErrorCode.GAME_NOT_PLAYABLE,
                () -> gameService.start(player.getId(), quizId, IP, UA));
        assertThat(quizService.viewVisible(GameMode.SOLO, PageRequest.of(0, 100)).getContent())
                .extracting(QuizResponse::id).doesNotContain(quizId);
    }

    @Test
    void anOpenSoloQuizCannotBeEdited() {
        Long quizId = data.soloQuiz(categoryId, 3);

        assertApiError(ErrorCode.QUIZ_INVALID_STATE, () -> quizService.updateSolo(null, quizId,
                new SoloQuizDto("Autre", null, categoryId, null, 1, 1000, 3, 5)));
    }

    // --- Outils -------------------------------------------------------------------

    /** Joueur credite de quoi lancer {@code games} parties solo. */
    private User funded(int games) {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, (long) TestData.SOLO_ENTRY * games);
        return player;
    }

    /** Joue toute la partie avec {@code correct} bonnes reponses, les premieres. */
    private GameResultResponse play(User player, GameStartResponse game, int correct) {
        GameStartResponse.CurrentQuestion current = game.current();
        GameTurnResponse turn = null;
        for (int i = 0; current != null; i++) {
            Long questionId = current.question().id();
            Long choiceId = i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            turn = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, choiceId));
            current = turn.next();
        }
        return turn.result();
    }
}
