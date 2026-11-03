package com.raikiservice.raf.admin;

import java.util.UUID;

import com.raikiservice.raf.admin.service.GameProfitService;
import com.raikiservice.raf.admin.service.GameProfitService.Period;
import com.raikiservice.raf.admin.web.dto.GameProfitResponse;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Quiz et comptes de test : invisibles des joueurs, exclus du resultat du jeu. */
class TestFlagsTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private TicketService ticketService;
    @Autowired private GameProfitService gameProfitService;
    @Autowired private UserRepository userRepository;

    @Test
    void aTestQuizIsHiddenFromRealPlayersAndLeftOutOfTheResult() {
        Long category = data.categoryWithQuestions(30);
        Long quiz = data.publishedQuiz(category, 3, 1000, 10, null, null);
        quizService.setTest(null, quiz, true);

        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () ->
                ticketService.purchase(player.getId(), quiz, UUID.randomUUID().toString()));
        assertThat(quizService.viewVisible(GameMode.GROUP, false, PageRequest.of(0, 100)).getContent())
                .extracting(QuizResponse::id).doesNotContain(quiz);
        assertThat(quizService.viewVisible(GameMode.GROUP, true, PageRequest.of(0, 100)).getContent())
                .extracting(QuizResponse::id).contains(quiz);

        // Un administrateur y joue : l'argent bouge, mais pas le resultat du jeu.
        GameProfitResponse before = gameProfitService.profit(Period.LAST_7_DAYS);
        User admin = data.admin();
        data.credit(admin.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        ticketService.purchase(admin.getId(), quiz, UUID.randomUUID().toString());
        assertThat(data.balance(admin.getId(), BalanceType.CREDIT)).isZero();

        GameProfitResponse after = gameProfitService.profit(Period.LAST_7_DAYS);
        assertThat(after.salesCredits()).isEqualTo(before.salesCredits());
        assertThat(after.ticketsSold()).isEqualTo(before.ticketsSold());
    }

    @Test
    void aQuizWithRealPlayersCannotBecomeATest() {
        Long category = data.categoryWithQuestions(30);
        Long quiz = data.publishedQuiz(category, 3, 1000, 10, null, null);
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        ticketService.purchase(player.getId(), quiz, UUID.randomUUID().toString());

        assertApiError(ErrorCode.QUIZ_HAS_REAL_PLAYERS, () -> quizService.setTest(null, quiz, true));
        assertThat(quizService.view(quiz).test()).isFalse();
    }

    @Test
    void aTestAccountsActivityIsLeftOutButItSeesTestQuizzes() {
        User tester = data.player();
        tester.setTest(true);
        userRepository.save(tester);

        Long category = data.categoryWithQuestions(30);
        Long normal = data.publishedQuiz(category, 3, 1000, 10, null, null);
        Long test = data.publishedQuiz(category, 3, 1000, 10, null, null);
        quizService.setTest(null, test, true);

        GameProfitResponse before = gameProfitService.profit(Period.LAST_7_DAYS);
        data.credit(tester.getId(), BalanceType.CREDIT, 2L * TestData.TICKET_PRICE);
        ticketService.purchase(tester.getId(), normal, UUID.randomUUID().toString());
        ticketService.purchase(tester.getId(), test, UUID.randomUUID().toString());

        GameProfitResponse after = gameProfitService.profit(Period.LAST_7_DAYS);
        assertThat(after.salesCredits()).isEqualTo(before.salesCredits());
        assertThat(after.ticketsSold()).isEqualTo(before.ticketsSold());

        // Un vrai joueur sur le quiz normal compte toujours.
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        ticketService.purchase(player.getId(), normal, UUID.randomUUID().toString());
        assertThat(gameProfitService.profit(Period.LAST_7_DAYS).salesCredits() - after.salesCredits())
                .isEqualTo(TestData.TICKET_PRICE);
    }
}
