package com.raikiservice.raf.quiz;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizAccess;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quiz masque aux joueurs : hors des listes, ferme aux nouvelles entrees, mais
 * toujours jouable par ceux qui ont deja un ticket. Le statut ne change pas.
 */
class QuizHiddenTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuizAccess quizAccess;
    @Autowired private TicketService ticketService;
    @Autowired private GameService gameService;

    private Long categoryId;

    @BeforeEach
    void category() {
        categoryId = data.categoryWithQuestions(30);
    }

    private List<Long> listedFor(User viewer, GameMode mode) {
        return quizService.viewVisible(mode, quizAccess.seesTestQuizzes(viewer.getId()), viewer.getId(),
                        PageRequest.of(0, 500))
                .getContent().stream().map(QuizResponse::id).toList();
    }

    private void buyTicket(User player, Long quizId) {
        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());
    }

    @Test
    void aHiddenGroupQuizStaysOpenForTicketHoldersOnly() {
        User holder = data.player();
        User newcomer = data.player();
        Long quizId = data.publishedQuiz(categoryId, 3, 0, 10, null, null);
        buyTicket(holder, quizId);

        quizService.setHidden(null, quizId, true);
        Quiz quiz = quizRepository.findById(quizId).orElseThrow();
        assertThat(quiz.isHidden()).isTrue();
        assertThat(quizService.view(quizId).status()).isEqualTo(quiz.getStatus());

        // Le detenteur d'un ticket le voit toujours ; un nouveau joueur ne le voit plus.
        assertThat(listedFor(holder, GameMode.GROUP)).contains(quizId);
        assertThat(listedFor(newcomer, GameMode.GROUP)).doesNotContain(quizId);
        quizAccess.assertVisible(quiz, holder.getId());
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> quizAccess.assertVisible(quiz, newcomer.getId()));

        // Plus de nouveau ticket, meme pour le detenteur.
        data.credit(newcomer.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> ticketService.purchase(newcomer.getId(), quizId, UUID.randomUUID().toString()));
        data.credit(holder.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        assertApiError(ErrorCode.QUIZ_HIDDEN,
                () -> ticketService.purchase(holder.getId(), quizId, UUID.randomUUID().toString()));

        // Le staff le voit toujours ; demasque, il revient pour tout le monde.
        assertThat(listedFor(data.admin(), GameMode.GROUP)).contains(quizId);
        quizService.setHidden(null, quizId, false);
        assertThat(listedFor(newcomer, GameMode.GROUP)).contains(quizId);
        ticketService.purchase(newcomer.getId(), quizId, UUID.randomUUID().toString());
    }

    @Test
    void aHiddenSoloQuizAcceptsNoNewGame() {
        User player = data.player();
        Long quizId = data.soloQuiz(categoryId, 3);
        data.credit(player.getId(), BalanceType.CREDIT, 2L * TestData.SOLO_ENTRY);

        quizService.setHidden(null, quizId, true);
        assertThat(listedFor(player, GameMode.SOLO)).doesNotContain(quizId);
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> gameService.start(player.getId(), quizId, "127.0.0.1", "junit"));

        quizService.setHidden(null, quizId, false);
        assertThat(listedFor(player, GameMode.SOLO)).contains(quizId);
        var game = gameService.start(player.getId(), quizId, "127.0.0.1", "junit");

        // Masque pendant sa partie : elle continue, mais il n'en lance pas d'autre.
        quizService.setHidden(null, quizId, true);
        assertThat(gameService.start(player.getId(), quizId, "127.0.0.1", "junit").sessionId())
                .isEqualTo(game.sessionId());
    }
}
