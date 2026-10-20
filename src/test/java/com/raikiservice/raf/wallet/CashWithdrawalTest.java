package com.raikiservice.raf.wallet;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.CounterWithdrawalService;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot 7 : retrait demande par le joueur, remis en especes au guichet. Le code
 * envoye au joueur present est exige pour la remise.
 */
class CashWithdrawalTest extends IntegrationTest {

    @Autowired private WithdrawalService withdrawalService;
    @Autowired private CounterWithdrawalService counterWithdrawalService;

    @Test
    void aCashRequestNeedsNoDestinationAndFreezesTheWinnings() {
        User player = playerWithWinnings();

        WithdrawalRequest request = requestCash(player, 2000);

        assertThat(request.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(request.getProvider()).isEqualTo(PaymentProvider.MANUAL);
        assertThat(request.getDestinationNumber()).isNull();
        assertThat(request.getNetHtg()).isEqualTo(2000 - 25);
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(3000);
    }

    @Test
    void anOperatorRequestStillNeedsADestination() {
        User player = playerWithWinnings();
        withdrawalService.sendWithdrawalOtp(player.getId());
        String code = sms.lastCode(player.getPhone());

        assertApiError(ErrorCode.WITHDRAWAL_DESTINATION_REQUIRED, () -> withdrawalService.request(
                player.getId(), 2000, PaymentProvider.MONCASH, null, code));
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(5000);
    }

    @Test
    void cashIsHandedOverOnlyWithThePlayersPickupCode() {
        User agent = data.admin();
        User player = playerWithWinnings();
        Long id = requestCash(player, 2000).getId();
        withdrawalService.approve(agent.getId(), id);

        assertApiError(ErrorCode.PICKUP_CODE_REQUIRED,
                () -> withdrawalService.markPaid(agent.getId(), id, "RECU-1", null));

        withdrawalService.sendPickupCode(agent.getId(), id);
        assertThat(sms.lastMessage(player.getPhone())).contains("1975 HTG").contains("guichet");
        String code = sms.lastCode(player.getPhone());

        assertApiError(ErrorCode.OTP_INVALID, () -> withdrawalService.markPaid(
                agent.getId(), id, null, "000000".equals(code) ? "111111" : "000000"));

        WithdrawalRequest paid = withdrawalService.markPaid(agent.getId(), id, null, code);
        assertThat(paid.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(paid.getPaidAt()).isNotNull();
        // Le gel a eu lieu a la demande : la remise ne debite rien de plus.
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(3000);

        assertApiError(ErrorCode.WITHDRAWAL_NOT_APPROVED,
                () -> withdrawalService.markPaid(agent.getId(), id, null, code));
    }

    @Test
    void thePickupCodeIsOnlySentForAnApprovedCashWithdrawal() {
        User agent = data.admin();
        User player = playerWithWinnings();
        Long cash = requestCash(player, 2000).getId();

        assertApiError(ErrorCode.WITHDRAWAL_NOT_APPROVED,
                () -> withdrawalService.sendPickupCode(agent.getId(), cash));

        withdrawalService.reject(agent.getId(), cash, "test");
        withdrawalService.sendWithdrawalOtp(player.getId());
        WithdrawalRequest moncash = withdrawalService.request(player.getId(), 2000,
                PaymentProvider.MONCASH, player.getPhone(), sms.lastCode(player.getPhone()));
        withdrawalService.approve(agent.getId(), moncash.getId());

        assertApiError(ErrorCode.NOT_A_CASH_WITHDRAWAL,
                () -> withdrawalService.sendPickupCode(agent.getId(), moncash.getId()));
        assertApiError(ErrorCode.PAYOUT_REFERENCE_REQUIRED,
                () -> withdrawalService.markPaid(agent.getId(), moncash.getId(), null, null));
        assertThat(withdrawalService.markPaid(agent.getId(), moncash.getId(), "MC-123", null)
                .getStatus()).isEqualTo(WithdrawalStatus.PAID);
    }

    @Test
    void aPickupCodeCannotConfirmACounterWithdrawalForAnotherAmount() {
        User agent = data.admin();
        User player = playerWithWinnings();

        // Un retrait saisi au guichet attend son code...
        Long counter = counterWithdrawalService.initiate(agent.getId(), player.getId(), 1000, null)
                .getId();
        // ... puis un code de remise est envoye pour une autre demande.
        Long cash = requestCash(player, 2000).getId();
        withdrawalService.approve(agent.getId(), cash);
        withdrawalService.sendPickupCode(agent.getId(), cash);
        String pickupCode = sms.lastCode(player.getPhone());

        assertApiError(ErrorCode.OTP_INVALID, () ->
                counterWithdrawalService.confirm(agent.getId(), counter, pickupCode, null));
    }

    private WithdrawalRequest requestCash(User player, long amountHtg) {
        withdrawalService.sendWithdrawalOtp(player.getId());
        return withdrawalService.request(player.getId(), amountHtg, PaymentProvider.MANUAL, null,
                sms.lastCode(player.getPhone()));
    }

    private User playerWithWinnings() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        return player;
    }
}
