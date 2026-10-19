package com.raikiservice.raf.admin;

import com.raikiservice.raf.admin.service.DashboardService;
import com.raikiservice.raf.admin.service.PlayerSearchService;
import com.raikiservice.raf.admin.web.dto.DashboardResponse;
import com.raikiservice.raf.admin.web.dto.PlayerSummary;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDashboardTest extends IntegrationTest {

    @Autowired private PlayerSearchService playerSearchService;
    @Autowired private DashboardService dashboardService;
    @Autowired private WithdrawalService withdrawalService;

    @Test
    void anAgentFindsAPlayerByPhoneWithoutCountryCodeOrByEmail() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 120);
        String localNumber = player.getPhone().substring(4);   // sans +509

        assertThat(playerSearchService.search(localNumber, PageRequest.of(0, 10)).getContent())
                .extracting(PlayerSummary::id).containsExactly(player.getId());

        PlayerSummary found = playerSearchService.search(player.getEmail().toUpperCase(),
                PageRequest.of(0, 10)).getContent().get(0);
        assertThat(found.winningsBalance()).isEqualTo(120);
        assertThat(found.winningsBalanceHtg()).isEqualTo(600);
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
        data.credit(player.getId(), BalanceType.WINNINGS, 1000);
        withdrawalService.sendWithdrawalOtp(player.getId());
        withdrawalService.request(player.getId(), 300, PaymentProvider.MANUAL, null,
                sms.lastCode(player.getPhone()));

        DashboardResponse dashboard = dashboardService.dashboard();
        assertThat(dashboard.pending().withdrawalsToReview()).isEqualTo(before + 1);
        assertThat(dashboard.players().total()).isPositive();
        assertThat(dashboard.balances().winningsBalanceHtg())
                .isEqualTo(dashboard.balances().winningsBalance() * dashboard.htgRate());
        assertThat(dashboard.quizzesByStatus()).containsKeys("DRAFT", "RUNNING", "FINISHED");
    }
}
