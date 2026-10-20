package com.raikiservice.raf.wallet;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.repository.WalletTransactionRepository;
import com.raikiservice.raf.wallet.repository.WithdrawalRequestRepository;
import com.raikiservice.raf.wallet.service.CounterWithdrawalService;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot 7 : retrait en especes au guichet, autorise par le code OTP du joueur.
 * Gains de depart : 1000 credits (5000 HTG au taux de 5).
 */
class CounterWithdrawalTest extends IntegrationTest {

    @Autowired private CounterWithdrawalService counterWithdrawalService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private WithdrawalRequestRepository withdrawalRequestRepository;
    @Autowired private WalletTransactionRepository walletTransactionRepository;

    @Test
    void initiationSendsAnSmsAnnouncingTheAmountAndDebitsNothing() {
        User agent = data.admin();
        User player = playerWithWinnings();

        WithdrawalRequest request = counterWithdrawalService.initiate(
                agent.getId(), player.getId(), 1000, "Guichet Petion-Ville");

        assertThat(request.getStatus()).isEqualTo(WithdrawalStatus.AWAITING_OTP);
        assertThat(request.getNetHtg()).isEqualTo(975);
        assertThat(request.getDestinationNumber()).isNull();
        assertThat(request.getProvider()).isEqualTo(PaymentProvider.MANUAL);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(5000);
        assertThat(sms.lastMessage(player.getPhone())).contains("975 HTG").contains("guichet");
    }

    @Test
    void theRightCodePaysOnceAndAWrongCodePaysNothing() throws Exception {
        User agent = data.admin();
        User player = playerWithWinnings();
        Long id = counterWithdrawalService.initiate(agent.getId(), player.getId(), 1000, null).getId();
        String code = sms.lastCode(player.getPhone());

        assertApiError(ErrorCode.OTP_INVALID, () ->
                counterWithdrawalService.confirm(agent.getId(), id, otherThan(code), null));
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(5000);

        // Double clic de l'agent : un seul debit.
        concurrently(3, () -> counterWithdrawalService.confirm(agent.getId(), id, code, "RECU-1"));
        WithdrawalRequest paid = counterWithdrawalService.confirm(agent.getId(), id, code, "RECU-1");

        assertThat(paid.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(4000);
        assertThat(walletTransactionRepository.findAll()).filteredOn(t ->
                t.getType() == WalletTransactionType.WITHDRAWAL
                        && String.valueOf(id).equals(t.getReferenceId()))
                .hasSize(1);
    }

    @Test
    void aCodeOnlyConfirmsTheLastAnnouncedAmount() {
        User agent = data.admin();
        User player = playerWithWinnings();

        Long first = counterWithdrawalService.initiate(agent.getId(), player.getId(), 2000, null).getId();
        String firstCode = sms.lastCode(player.getPhone());
        Long second = counterWithdrawalService.initiate(agent.getId(), player.getId(), 1000, null).getId();
        String secondCode = sms.lastCode(player.getPhone());

        assertThat(withdrawalRequestRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(WithdrawalStatus.CANCELLED);
        assertApiError(ErrorCode.WITHDRAWAL_NOT_PENDING, () ->
                counterWithdrawalService.confirm(agent.getId(), first, secondCode, null));
        assertApiError(ErrorCode.WITHDRAWAL_NOT_PENDING, () ->
                counterWithdrawalService.confirm(agent.getId(), first, firstCode, null));

        counterWithdrawalService.confirm(agent.getId(), second, secondCode, null);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(4000);
    }

    @Test
    void anOnlineWithdrawalCodeCannotBeUsedAtTheCounter() {
        User agent = data.admin();
        User player = playerWithWinnings();

        Long id = counterWithdrawalService.initiate(agent.getId(), player.getId(), 1000, null).getId();
        withdrawalService.sendWithdrawalOtp(player.getId());
        String onlineCode = sms.lastCode(player.getPhone());

        assertApiError(ErrorCode.OTP_INVALID, () ->
                counterWithdrawalService.confirm(agent.getId(), id, onlineCode, null));
    }

    @Test
    void aCancelledWithdrawalCannotBeConfirmed() {
        User agent = data.admin();
        User player = playerWithWinnings();
        Long id = counterWithdrawalService.initiate(agent.getId(), player.getId(), 1000, null).getId();
        String code = sms.lastCode(player.getPhone());

        counterWithdrawalService.cancel(agent.getId(), id);

        assertApiError(ErrorCode.WITHDRAWAL_NOT_PENDING, () ->
                counterWithdrawalService.confirm(agent.getId(), id, code, null));
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(5000);
    }

    @Test
    void initiationApplysTheSameGuardsAsAnOnlineWithdrawal() {
        User agent = data.admin();
        User unverified = data.player(false);
        User player = playerWithWinnings();

        assertApiError(ErrorCode.PHONE_NOT_VERIFIED, () ->
                counterWithdrawalService.initiate(agent.getId(), unverified.getId(), 1000, null));
        assertApiError(ErrorCode.INSUFFICIENT_FUNDS, () ->
                counterWithdrawalService.initiate(agent.getId(), player.getId(), 50_000, null));
        assertApiError(ErrorCode.WITHDRAWAL_BELOW_MINIMUM, () ->
                counterWithdrawalService.initiate(agent.getId(), player.getId(), 500, null));
        assertThat(sms.lastMessage(player.getPhone())).isNull();
    }

    @Test
    void theOnlineWithdrawalStillFreezesThenRefundsOnRejection() {
        User agent = data.admin();
        User player = playerWithWinnings();

        withdrawalService.sendWithdrawalOtp(player.getId());
        WithdrawalRequest request = withdrawalService.request(player.getId(), 1500,
                PaymentProvider.MONCASH, player.getPhone(), sms.lastCode(player.getPhone()));

        assertThat(request.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(3500);

        withdrawalService.reject(agent.getId(), request.getId(), "test");
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(5000);
    }

    @Test
    void theSameCodeSentTwiceAtOnceOpensASingleWithdrawal() throws Exception {
        User player = playerWithWinnings();
        withdrawalService.sendWithdrawalOtp(player.getId());
        String code = sms.lastCode(player.getPhone());

        concurrently(2, () -> withdrawalService.request(player.getId(), 1500,
                PaymentProvider.MONCASH, player.getPhone(), code));

        assertThat(withdrawalRequestRepository.findByUserIdAndStatus(
                player.getId(), WithdrawalStatus.PENDING)).hasSize(1);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(3500);
    }

    private User playerWithWinnings() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        return player;
    }

    private static String otherThan(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }
}
