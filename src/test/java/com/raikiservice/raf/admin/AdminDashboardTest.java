package com.raikiservice.raf.admin;

import com.raikiservice.raf.admin.service.DashboardService;
import com.raikiservice.raf.admin.service.GameProfitService;
import com.raikiservice.raf.admin.service.GameProfitService.Period;
import com.raikiservice.raf.admin.service.PlayerSearchService;
import com.raikiservice.raf.admin.web.dto.DashboardResponse;
import com.raikiservice.raf.admin.web.dto.GameProfitResponse;
import com.raikiservice.raf.admin.web.dto.PlayerSummary;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDashboardTest extends IntegrationTest {

    @Autowired private PlayerSearchService playerSearchService;
    @Autowired private DashboardService dashboardService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private GameProfitService gameProfitService;
    @Autowired private QuizService quizService;
    @Autowired private TicketService ticketService;

    @Test
    void anAgentFindsAPlayerByPhoneWithoutCountryCodeOrByEmail() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 120);
        String localNumber = player.getPhone().substring(4);   // sans +509

        assertThat(playerSearchService.search(localNumber, PageRequest.of(0, 10)).getContent())
                .extracting(PlayerSummary::id).containsExactly(player.getId());

        PlayerSummary found = playerSearchService.search(player.getEmail().toUpperCase(),
                PageRequest.of(0, 10)).getContent().get(0);
        assertThat(found.winningsBalanceHtg()).isEqualTo(120);
        assertThat(found.phoneVerified()).isTrue();
    }

    @Test
    void aSearchWithWildcardsMatchesLiterally() {
        assertThat(playerSearchService.search("%", PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void theDashboardCountsPendingWithdrawals() {
        long before = dashboardService.dashboard().pending().withdrawalsToReview();

        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        withdrawalService.sendWithdrawalOtp(player.getId());
        withdrawalService.request(player.getId(), 1500, PaymentProvider.MANUAL, null,
                sms.lastCode(player.getPhone()));

        DashboardResponse dashboard = dashboardService.dashboard();
        assertThat(dashboard.pending().withdrawalsToReview()).isEqualTo(before + 1);
        assertThat(dashboard.players().total()).isPositive();
        // Les gains sont tenus en HTG : pas de conversion par le taux du credit.
        assertThat(dashboard.balances().winningsBalanceHtg()).isGreaterThanOrEqualTo(3500);
        assertThat(dashboard.balances().creditBalanceHtg())
                .isEqualTo(dashboard.balances().creditBalance() * dashboard.htgRate());
        assertThat(dashboard.quizzesByStatus()).containsKeys("DRAFT", "RUNNING", "FINISHED");
    }

    @Test
    void theGameResultIsSalesMinusRefundsMinusPrizesPlusWithdrawalFees() {
        GameProfitResponse before = gameProfitService.profit(Period.LAST_7_DAYS);

        // Deux tickets vendus, dont un rembourse par l'annulation de son quiz.
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 2L * TestData.TICKET_PRICE);
        Long category = data.categoryWithQuestions(30);
        Long kept = data.publishedQuiz(category, 3, 1000, 10, null, null);
        Long cancelled = data.publishedQuiz(category, 3, 1000, 10, null, null);
        ticketService.purchase(player.getId(), kept, UUID.randomUUID().toString());
        ticketService.purchase(player.getId(), cancelled, UUID.randomUUID().toString());
        quizService.cancel(null, cancelled, "test");

        // Un retrait verse : ses frais sont une recette.
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        withdrawalService.sendWithdrawalOtp(player.getId());
        WithdrawalRequest withdrawal = withdrawalService.request(player.getId(), 1500, PaymentProvider.MONCASH,
                player.getPhone(), sms.lastCode(player.getPhone()));
        withdrawalService.approve(null, withdrawal.getId());
        withdrawalService.markPaid(null, withdrawal.getId(), "RECU-PROFIT", null);

        GameProfitResponse after = gameProfitService.profit(Period.LAST_7_DAYS);
        long rate = after.htgRate();
        assertThat(after.salesCredits() - before.salesCredits()).isEqualTo(2L * TestData.TICKET_PRICE);
        assertThat(after.refundsCredits() - before.refundsCredits()).isEqualTo(TestData.TICKET_PRICE);
        assertThat(after.withdrawalFeesHtg() - before.withdrawalFeesHtg()).isEqualTo(withdrawal.getFeeHtg());
        assertThat(after.ticketsSold() - before.ticketsSold()).isEqualTo(1);
        assertThat(after.resultHtg() - before.resultHtg())
                .isEqualTo(TestData.TICKET_PRICE * rate + withdrawal.getFeeHtg());
        assertThat(after.resultHtg()).isEqualTo(
                after.salesHtg() - after.refundsHtg() - after.prizesHtg() + after.withdrawalFeesHtg());
    }

    @Test
    void lastMonthEndsWhereThisMonthBegins() {
        GameProfitResponse thisMonth = gameProfitService.profit(Period.THIS_MONTH);
        GameProfitResponse lastMonth = gameProfitService.profit(Period.LAST_MONTH);
        GameProfitResponse allTime = gameProfitService.profit(Period.ALL_TIME);

        assertThat(lastMonth.to()).isEqualTo(thisMonth.from());
        assertThat(lastMonth.from()).isBefore(lastMonth.to());
        assertThat(allTime.from()).isNull();
        assertThat(allTime.salesCredits()).isGreaterThanOrEqualTo(thisMonth.salesCredits());
    }
}
