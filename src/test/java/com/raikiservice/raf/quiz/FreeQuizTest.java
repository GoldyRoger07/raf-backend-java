package com.raikiservice.raf.quiz;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.service.WalletService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quiz gratuit : le ticket ne coute rien et n'ecrit aucun mouvement, un seul
 * ticket par joueur, et la comptabilite reste coherente.
 */
class FreeQuizTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private TicketService ticketService;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private WalletService walletService;
    @Autowired private ReconciliationService reconciliationService;

    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    @Test
    void aFreeTicketCostsNothingAndWritesNoMovement() {
        User player = data.player();
        walletService.getOrCreate(player.getId());
        Long quizId = data.freeQuiz(categoryId, 3, 10, null);

        QuizResponse view = quizService.view(quizId);
        assertThat(view.free()).isTrue();
        assertThat(view.ticketPriceCredits()).isZero();

        // Aucun credit sur le compte : l'achat passe quand meme.
        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());

        Ticket ticket = ticketRepository.findByQuizIdAndUserId(quizId, player.getId()).get(0);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        assertThat(ticket.getPricePaidCredits()).isZero();
        assertThat(ticket.getTransactionId()).isNull();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
        assertThat(walletService.history(player.getId(), PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void aFreeQuizAllowsOneTicketPerPlayerWhateverTheAdminAsked() {
        User player = data.player();
        Long quizId = data.freeQuiz(categoryId, 3, 10, 5);

        assertThat(quizService.view(quizId).maxTicketsPerPlayer()).isEqualTo(1);

        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());
        assertApiError(ErrorCode.QUIZ_PLAYER_LIMIT,
                () -> ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString()));
    }

    @Test
    void cancellingAFreeQuizClosesTicketsWithoutRefund() {
        User player = data.player();
        walletService.getOrCreate(player.getId());
        Long quizId = data.freeQuiz(categoryId, 3, 10, null);
        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());

        quizService.cancel(null, quizId, "test");

        Ticket ticket = ticketRepository.findByQuizIdAndUserId(quizId, player.getId()).get(0);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.REFUNDED);
        assertThat(ticket.getRefundTransactionId()).isNull();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
    }

    @Test
    void freeTicketsAreNotReconciliationFindings() {
        User holder = data.player();
        User cancelled = data.player();
        Long quizId = data.freeQuiz(categoryId, 3, 10, null);
        Long cancelledQuiz = data.freeQuiz(categoryId, 3, 10, null);
        ticketService.purchase(holder.getId(), quizId, UUID.randomUUID().toString());
        ticketService.purchase(cancelled.getId(), cancelledQuiz, UUID.randomUUID().toString());
        quizService.cancel(null, cancelledQuiz, "test");

        List<Long> involved = List.of(holder.getId(), cancelled.getId());
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> involved.contains(finding.userId()))
                .isEmpty();
    }
}
