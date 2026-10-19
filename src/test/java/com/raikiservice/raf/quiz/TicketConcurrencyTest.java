package com.raikiservice.raf.quiz;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.TicketResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.repository.WalletTransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lots 3 et 5 : l'achat de ticket sous concurrence. Le solde ne devient jamais
 * negatif, les plafonds tiennent, et une meme cle d'idempotence ne debite qu'une
 * fois.
 */
class TicketConcurrencyTest extends IntegrationTest {

    @Autowired private TicketService ticketService;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private com.raikiservice.raf.common.audit.AuditLogRepository auditLogRepository;

    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    @Test
    void concurrentPurchasesNeverOverdrawTheWallet() throws Exception {
        User player = data.player();
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, null);
        data.credit(player.getId(), BalanceType.CREDIT, 5L * TestData.TICKET_PRICE);

        List<TicketResponse> bought = concurrently(10, () ->
                ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString()));

        assertThat(bought).hasSize(5);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
        assertThat(ticketRepository.countByQuizIdAndUserIdAndStatusNot(
                quizId, player.getId(), TicketStatus.REFUNDED)).isEqualTo(5);
    }

    @Test
    void theSameIdempotencyKeyBuysOneTicketAndDebitsOnce() throws Exception {
        User player = data.player();
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, null);
        data.credit(player.getId(), BalanceType.CREDIT, 10L * TestData.TICKET_PRICE);
        String key = UUID.randomUUID().toString();

        // Chaque envoi recoit le meme ticket : aucun ne bute sur la contrainte d'unicite.
        List<TicketResponse> responses = concurrently(4,
                () -> ticketService.purchase(player.getId(), quizId, key));
        assertThat(responses).hasSize(4);
        assertThat(responses).extracting(TicketResponse::id).containsOnly(responses.get(0).id());
        ticketService.purchase(player.getId(), quizId, key);

        assertThat(ticketRepository.findByQuizIdAndUserId(quizId, player.getId())).hasSize(1);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT))
                .isEqualTo(9L * TestData.TICKET_PRICE);
        String ticketId = String.valueOf(
                ticketRepository.findByQuizIdAndUserId(quizId, player.getId()).get(0).getId());
        assertThat(walletTransactionRepository.findAll()).filteredOn(t ->
                t.getType() == WalletTransactionType.TICKET_PURCHASE
                        && ticketId.equals(t.getReferenceId()))
                .hasSize(1);
    }

    @Test
    void theTotalTicketCapHoldsUnderConcurrency() throws Exception {
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, 2, null);
        List<User> players = List.of(data.player(), data.player(), data.player(),
                data.player());
        players.forEach(p -> data.credit(p.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE));

        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        List<TicketResponse> bought = concurrently(players.size(), () -> ticketService.purchase(
                players.get(next.getAndIncrement()).getId(), quizId, UUID.randomUUID().toString()));

        assertThat(bought).hasSize(2);
        assertThat(ticketRepository.countByQuizIdAndStatusNot(quizId, TicketStatus.REFUNDED))
                .isEqualTo(2);
    }

    /**
     * Plus d'achats simultanes que de connexions dans le pool (10). Avant que
     * l'audit ne soit ecrit hors du thread de la requete, chaque achat tenait sa
     * connexion en attendant celle de l'audit : tout se figeait 30 s et les traces
     * etaient perdues.
     */
    @Test
    void moreSimultaneousPurchasesThanPooledConnectionsNeitherStallNorLoseAudit()
            throws Exception {
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, null);
        List<User> players = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            User player = data.player();
            data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
            players.add(player);
        }

        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        long start = System.currentTimeMillis();
        List<TicketResponse> bought = concurrently(players.size(), () -> ticketService.purchase(
                players.get(next.getAndIncrement()).getId(), quizId, UUID.randomUUID().toString()));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(bought).hasSize(players.size());
        assertThat(elapsed).as("duree des achats (ms)").isLessThan(10_000);

        java.util.Set<String> ticketIds = new java.util.HashSet<>();
        bought.forEach(ticket -> ticketIds.add(String.valueOf(ticket.id())));
        eventually(() -> assertThat(auditLogRepository.findAll()).filteredOn(entry ->
                "TICKET_PURCHASED".equals(entry.getAction()) && ticketIds.contains(entry.getEntityId()))
                .hasSize(players.size()));
    }

    @Test
    void thePerPlayerCapIsEnforced() {
        User player = data.player();
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, 2);
        data.credit(player.getId(), BalanceType.CREDIT, 5L * TestData.TICKET_PRICE);

        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());
        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());

        assertApiError(ErrorCode.QUIZ_PLAYER_LIMIT, () ->
                ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString()));
    }

    @Test
    void insufficientCreditsAreRefused() {
        User player = data.player();
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, null);

        assertApiError(ErrorCode.INSUFFICIENT_FUNDS, () ->
                ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString()));
        assertThat(ticketRepository.findByQuizIdAndUserId(quizId, player.getId())).isEmpty();
    }
}
