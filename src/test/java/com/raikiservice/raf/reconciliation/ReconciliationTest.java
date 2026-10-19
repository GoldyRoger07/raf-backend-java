package com.raikiservice.raf.reconciliation;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationCheck;
import com.raikiservice.raf.reconciliation.domain.ReconciliationStatus;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.reconciliation.web.dto.ReconciliationRunResponse;
import com.raikiservice.raf.reconciliation.web.dto.ReconciliationRunResponse.Finding;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le rapprochement comptable. La base de test est partagee : un bilan propre
 * prouve aussi que tout ce que les autres tests ont fait (achats, gains, retraits,
 * guichet) a laisse des comptes coherents.
 *
 * <p>Les tests de detection falsifient volontairement une ligne, puis la
 * retablissent : un ecart n'a qu'une cause possible, la falsification.
 */
class ReconciliationTest extends IntegrationTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private TicketService ticketService;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aConsistentDatabaseHasNoFinding() {
        User player = data.player();
        buyTicket(player);

        ReconciliationRunResponse run = reconciliationService.run(ReconciliationTrigger.MANUAL, null);

        assertThat(run.findings()).as("ecarts").isEmpty();
        assertThat(run.status()).isEqualTo(ReconciliationStatus.OK);
        assertThat(run.walletsChecked()).isPositive();
        assertThat(run.transactionsChecked()).isPositive();
    }

    @Test
    void aBalanceEditedOutsideTheLedgerIsDetected() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 100);

        tampered("UPDATE wallets SET credit_balance = credit_balance + 7 WHERE user_id = ?",
                "UPDATE wallets SET credit_balance = credit_balance - 7 WHERE user_id = ?",
                player.getId(), () -> {
                    List<Finding> found = findingsFor(player.getId());
                    assertThat(found).extracting(Finding::check)
                            .containsExactly(ReconciliationCheck.LEDGER_MISMATCH);
                    assertThat(found.get(0).expectedValue()).isEqualTo(100);
                    assertThat(found.get(0).actualValue()).isEqualTo(107);
                });
    }

    @Test
    void aRewrittenTransactionBreaksTheChainAndTheLedger() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 100);
        Ticket ticket = buyTicket(player);

        tampered("UPDATE wallet_transactions SET amount = amount - 1 WHERE id = ?",
                "UPDATE wallet_transactions SET amount = amount + 1 WHERE id = ?",
                ticket.getTransactionId(), () -> assertThat(findingsFor(player.getId()))
                        .extracting(Finding::check)
                        .contains(ReconciliationCheck.LEDGER_MISMATCH,
                                ReconciliationCheck.BALANCE_CHAIN_BREAK,
                                ReconciliationCheck.TICKET_DEBIT));
    }

    @Test
    void aTicketDetachedFromItsDebitIsDetectedFromBothSides() {
        User player = data.player();
        Ticket ticket = buyTicket(player);
        Long debitId = ticket.getTransactionId();

        tampered("UPDATE tickets SET transaction_id = NULL WHERE id = ?",
                "UPDATE tickets SET transaction_id = " + debitId + " WHERE id = ?",
                ticket.getId(), () -> assertThat(findingsFor(player.getId()))
                        .extracting(Finding::check)
                        .containsExactlyInAnyOrder(ReconciliationCheck.TICKET_DEBIT,
                                ReconciliationCheck.ORPHAN_TRANSACTION));
    }

    @Test
    void aRejectedWithdrawalWithoutRefundIsDetected() {
        User agent = data.admin();
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 1000);
        withdrawalService.sendWithdrawalOtp(player.getId());
        WithdrawalRequest request = withdrawalService.request(player.getId(), 300,
                PaymentProvider.MONCASH, player.getPhone(), sms.lastCode(player.getPhone()));
        Long refundId = withdrawalService.reject(agent.getId(), request.getId(), "test")
                .getRefundTransactionId();

        tampered("UPDATE withdrawal_requests SET refund_transaction_id = NULL WHERE id = ?",
                "UPDATE withdrawal_requests SET refund_transaction_id = " + refundId + " WHERE id = ?",
                request.getId(), () -> assertThat(findingsFor(player.getId()))
                        .extracting(Finding::check)
                        .containsExactlyInAnyOrder(ReconciliationCheck.WITHDRAWAL_REFUND,
                                ReconciliationCheck.ORPHAN_TRANSACTION));
    }

    @Test
    void aDebitOnAnotherPlayersWalletIsDetected() {
        User player = data.player();
        User other = data.player();
        data.credit(other.getId(), BalanceType.CREDIT, 100);
        Ticket ticket = buyTicket(player);

        // Le ticket de "player" pointe vers un mouvement du portefeuille de "other".
        Long othersMovement = jdbc.queryForObject("SELECT t.id FROM wallet_transactions t "
                + "JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ? ORDER BY t.id LIMIT 1",
                Long.class, other.getId());
        tampered("UPDATE tickets SET transaction_id = " + othersMovement + " WHERE id = ?",
                "UPDATE tickets SET transaction_id = " + ticket.getTransactionId() + " WHERE id = ?",
                ticket.getId(), () -> assertThat(findingsFor(player.getId()))
                        .filteredOn(f -> f.check() == ReconciliationCheck.TICKET_DEBIT)
                        .isNotEmpty());
    }

    @Test
    void runsAreStoredWithTheirFindings() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 100);

        tampered("UPDATE wallets SET credit_balance = credit_balance + 1 WHERE user_id = ?",
                "UPDATE wallets SET credit_balance = credit_balance - 1 WHERE user_id = ?",
                player.getId(), () -> {
                    ReconciliationRunResponse run = reconciliationService.run(
                            ReconciliationTrigger.MANUAL, null);
                    assertThat(run.status()).isEqualTo(ReconciliationStatus.DISCREPANCIES);

                    ReconciliationRunResponse stored = reconciliationService.detail(run.id());
                    assertThat(stored.findingCount()).isEqualTo(run.findingCount());
                    assertThat(stored.findings()).hasSize(run.findings().size());
                    assertThat(stored.findings()).extracting(Finding::userId).contains(player.getId());
                });
    }

    // --- Outils -------------------------------------------------------------------

    private Ticket buyTicket(User player) {
        Long categoryId = data.categoryWithQuestions(30);
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, null);
        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        Long ticketId = ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString()).id();
        return ticketRepository.findById(ticketId).orElseThrow();
    }

    private List<Finding> findingsFor(Long userId) {
        return reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings().stream()
                .filter(finding -> userId.equals(finding.userId()))
                .toList();
    }

    /** Falsifie une ligne, verifie, puis la retablit quoi qu'il arrive. */
    private void tampered(String tamperSql, String restoreSql, Long id, Runnable assertions) {
        jdbc.update(tamperSql, id);
        try {
            assertions.run();
        } finally {
            jdbc.update(restoreSql, id);
        }
    }
}
