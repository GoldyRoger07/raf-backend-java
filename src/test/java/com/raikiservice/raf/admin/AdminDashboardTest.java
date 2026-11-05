package com.raikiservice.raf.admin;

import com.raikiservice.raf.admin.service.DashboardService;
import com.raikiservice.raf.admin.service.GameProfitService;
import com.raikiservice.raf.admin.service.GameProfitService.Period;
import com.raikiservice.raf.admin.service.PlayerSearchService;
import com.raikiservice.raf.admin.web.dto.DashboardResponse;
import com.raikiservice.raf.admin.web.dto.GameProfitResponse;
import com.raikiservice.raf.admin.web.dto.PlayerSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.admin.service.PlayerFilter;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ErrorCode;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDashboardTest extends IntegrationTest {

    @Autowired private PlayerSearchService playerSearchService;
    @Autowired private UserRepository userRepository;
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
    void filtersCombineWithTheSearch() {
        User verifiedWithCredit = data.player();
        data.credit(verifiedWithCredit.getId(), BalanceType.CREDIT, 5);
        User unverified = data.player(false);
        unverified.setEmailVerifiedAt(null);
        userRepository.save(unverified);
        User suspendedWithWinnings = data.player();
        suspendedWithWinnings.setStatus(UserStatus.SUSPENDED);
        suspendedWithWinnings.setTest(true);
        userRepository.save(suspendedWithWinnings);
        data.credit(suspendedWithWinnings.getId(), BalanceType.WINNINGS, 300);
        List<Long> mine = List.of(verifiedWithCredit.getId(), unverified.getId(), suspendedWithWinnings.getId());
        String run = runPrefix(verifiedWithCredit);

        assertThat(ids(filter(run, true, null, null, null, null, null), mine))
                .containsExactlyInAnyOrder(verifiedWithCredit.getId(), suspendedWithWinnings.getId());
        assertThat(ids(filter(run, false, false, null, null, null, null), mine))
                .containsExactly(unverified.getId());
        assertThat(ids(filter(run, null, null, true, null, null, null), mine))
                .containsExactly(verifiedWithCredit.getId());
        assertThat(ids(filter(run, null, null, false, true, null, null), mine))
                .containsExactly(suspendedWithWinnings.getId());
        assertThat(ids(filter(run, null, null, null, null, UserStatus.SUSPENDED, true), mine))
                .containsExactly(suspendedWithWinnings.getId());
        assertThat(ids(filter(run, null, null, null, null, null, false), mine))
                .containsExactlyInAnyOrder(verifiedWithCredit.getId(), unverified.getId());

        // Le total suit les filtres.
        PlayerFilter onlyCredit = filter(verifiedWithCredit.getEmail(), null, null, true, null, null, null);
        assertThat(playerSearchService.search(onlyCredit, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        PlayerFilter none = filter(verifiedWithCredit.getEmail(), null, null, false, null, null, null);
        assertThat(playerSearchService.search(none, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void theRegistrationDateFilterUsesHaitiDays() {
        User player = data.player();
        LocalDate today = LocalDate.now(ZoneId.of("America/Port-au-Prince"));
        String email = player.getEmail();

        assertThat(playerSearchService.search(dates(email, today, today), PageRequest.of(0, 10)).getContent())
                .extracting(PlayerSummary::id).containsExactly(player.getId());
        assertThat(playerSearchService.search(dates(email, today.plusDays(1), null), PageRequest.of(0, 10))
                .getContent()).isEmpty();
        assertThat(playerSearchService.search(dates(email, null, today.minusDays(1)), PageRequest.of(0, 10))
                .getContent()).isEmpty();
    }

    @Test
    void playersAreSortedByTheRequestedColumn() {
        User rich = data.player();
        data.credit(rich.getId(), BalanceType.CREDIT, 50);
        User poor = data.player();
        data.credit(poor.getId(), BalanceType.CREDIT, 2);
        User none = data.player();
        List<Long> mine = List.of(rich.getId(), poor.getId(), none.getId());
        PlayerFilter run = PlayerFilter.query(runPrefix(rich));

        assertThat(ids(playerSearchService.search(run,
                PageRequest.of(0, 1000, Sort.by("creditBalance").descending())), mine))
                .containsExactly(rich.getId(), poor.getId(), none.getId());
        assertThat(ids(playerSearchService.search(run,
                PageRequest.of(0, 1000, Sort.by("creditBalance").ascending())), mine))
                .containsExactly(none.getId(), poor.getId(), rich.getId());
        // Le tri par nom ou email passe aussi (les noms Joueur<n> ne se comparent pas simplement).
        assertThat(playerSearchService.search(run, PageRequest.of(0, 10, Sort.by("name", "email")))
                .getContent()).isNotEmpty();
        assertThat(playerSearchService.search(run, PageRequest.of(0, 10, Sort.by("email").descending()))
                .getContent()).isNotEmpty();
        // Sans tri demande : les derniers inscrits d'abord.
        assertThat(ids(playerSearchService.search(run, PageRequest.of(0, 1000)), mine))
                .containsExactly(none.getId(), poor.getId(), rich.getId());
    }

    @Test
    void anUnknownSortColumnIsRefused() {
        assertApiError(ErrorCode.VALIDATION_ERROR, () -> playerSearchService.search(PlayerFilter.query(null),
                PageRequest.of(0, 10, Sort.by("password_hash"))));
    }

    /** Prefixe commun aux emails des joueurs crees par ce lancement de tests. */
    private static String runPrefix(User player) {
        return player.getEmail().substring(0, player.getEmail().indexOf('-') + 1);
    }

    private static PlayerFilter filter(String query, Boolean emailVerified, Boolean phoneVerified,
            Boolean hasCredit, Boolean hasWinnings, UserStatus status, Boolean test) {
        return new PlayerFilter(query, emailVerified, phoneVerified, hasCredit, hasWinnings, status, test,
                null, null);
    }

    private static PlayerFilter dates(String query, LocalDate from, LocalDate to) {
        return new PlayerFilter(query, null, null, null, null, null, null, from, to);
    }

    private List<Long> ids(PlayerFilter filter, List<Long> among) {
        return ids(playerSearchService.search(filter, PageRequest.of(0, 1000)), among);
    }

    private static List<Long> ids(Page<PlayerSummary> page, List<Long> among) {
        return page.getContent().stream().map(PlayerSummary::id).filter(among::contains).toList();
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
