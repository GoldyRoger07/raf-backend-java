package com.raikiservice.raf.wallet;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.PayoutSpecialCaseService;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Retraits verses par tranches, et cloture avec le reste rendu aux gains. */
class WithdrawalInstallmentTest extends IntegrationTest {

    @Autowired private WithdrawalService withdrawalService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private PayoutSpecialCaseService specialCases;

    /** Joueur avec des gains, dans la liste des cas particuliers : ses retraits se fractionnent. */
    private User playerWithWinnings() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        specialCases.add(data.admin().getId(), player.getId(), null);
        return player;
    }

    private WithdrawalRequest request(User player, long amount, PaymentProvider provider) {
        withdrawalService.sendWithdrawalOtp(player.getId());
        return withdrawalService.request(player.getId(), amount, provider,
                provider == PaymentProvider.MANUAL ? null : "+50937001122", sms.lastCode(player.getPhone()));
    }

    private Long approved(User player, long amount) {
        Long id = request(player, amount, PaymentProvider.MONCASH).getId();
        withdrawalService.approve(data.admin().getId(), id);
        return id;
    }

    @Test
    void aWithdrawalIsPaidInSeveralInstallments() {
        User player = playerWithWinnings();
        Long id = approved(player, 2000);
        Long admin = data.admin().getId();

        WithdrawalRequest first = withdrawalService.payInstallment(admin, id, 500, "MC-1");
        long net = first.getNetHtg();
        assertThat(first.getStatus()).isEqualTo(WithdrawalStatus.PARTIALLY_PAID);

        WithdrawalRequest second = withdrawalService.payInstallment(admin, id, 300, "MC-2");
        assertThat(second.getStatus()).isEqualTo(WithdrawalStatus.PARTIALLY_PAID);
        assertThat(second.getPaidHtg()).isEqualTo(800);
        assertThat(second.getPaidAt()).isNull();

        // Une seule demande a la fois : un retrait verse en partie bloque la suivante.
        withdrawalService.sendWithdrawalOtp(player.getId());
        String code = sms.lastCode(player.getPhone());
        assertApiError(ErrorCode.WITHDRAWAL_PENDING_EXISTS, () -> withdrawalService.request(
                player.getId(), 1000, PaymentProvider.MONCASH, "+50937001122", code));

        // « Marquer paye » sur un retrait verse en partie verse le reste.
        WithdrawalRequest done = withdrawalService.markPaid(admin, id, "MC-3", null);
        assertThat(done.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(done.getPaidHtg()).isEqualTo(net);
        assertThat(done.getPaidAt()).isNotNull();
        assertThat(withdrawalService.installments(id))
                .extracting(p -> p.getAmountHtg())
                .containsExactly(500L, 300L, net - 800);

        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void installmentsAreCheckedAgainstTheRemainderAndTheProvider() {
        User player = playerWithWinnings();
        Long pending = request(player, 2000, PaymentProvider.MONCASH).getId();
        Long admin = data.admin().getId();
        assertApiError(ErrorCode.WITHDRAWAL_NOT_APPROVED,
                () -> withdrawalService.payInstallment(admin, pending, 100, "MC-1"));

        withdrawalService.approve(admin, pending);
        long net = withdrawalService.payInstallment(admin, pending, 100, "MC-1").getNetHtg();
        assertApiError(ErrorCode.WITHDRAWAL_INSTALLMENT_INVALID,
                () -> withdrawalService.payInstallment(admin, pending, 0, "MC-2"));
        assertApiError(ErrorCode.WITHDRAWAL_INSTALLMENT_INVALID,
                () -> withdrawalService.payInstallment(admin, pending, net, "MC-2"));
        assertApiError(ErrorCode.PAYOUT_REFERENCE_REQUIRED,
                () -> withdrawalService.payInstallment(admin, pending, 100, " "));
        // Verse en partie : plus de rejet, seulement la cloture avec le reste rendu.
        assertApiError(ErrorCode.WITHDRAWAL_NOT_PENDING,
                () -> withdrawalService.reject(admin, pending, "test"));

        User cash = playerWithWinnings();
        Long cashId = request(cash, 2000, PaymentProvider.MANUAL).getId();
        withdrawalService.approve(admin, cashId);
        assertApiError(ErrorCode.WITHDRAWAL_INSTALLMENT_CASH,
                () -> withdrawalService.payInstallment(admin, cashId, 100, "R-1"));
    }

    @Test
    void onlySpecialCasesAreSplitAndAStartedWithdrawalIsFinishedAfterRemoval() {
        Long admin = data.admin().getId();
        User regular = data.player();
        data.credit(regular.getId(), BalanceType.WINNINGS, 5000);
        Long regularId = approved(regular, 2000);
        assertApiError(ErrorCode.WITHDRAWAL_NOT_SPECIAL_CASE,
                () -> withdrawalService.payInstallment(admin, regularId, 100, "MC-1"));

        User listed = playerWithWinnings();
        Long listedId = approved(listed, 2000);
        withdrawalService.payInstallment(admin, listedId, 100, "MC-1");
        specialCases.remove(admin, listed.getId());

        // Retire de la liste : son retrait commence se termine normalement.
        assertThat(withdrawalService.payInstallment(admin, listedId, 100, "MC-2").getPaidHtg()).isEqualTo(200);
        assertThat(specialCases.contains(listed.getId())).isFalse();
    }

    @Test
    void closingAPartlyPaidWithdrawalGivesTheRestBack() {
        User player = playerWithWinnings();
        Long id = approved(player, 2000);
        Long admin = data.admin().getId();
        assertApiError(ErrorCode.WITHDRAWAL_NOT_PARTIALLY_PAID,
                () -> withdrawalService.closeWithRefund(admin, id, "fonds insuffisants"));

        WithdrawalRequest partial = withdrawalService.payInstallment(admin, id, 600, "MC-1");
        long before = data.balance(player.getId(), BalanceType.WINNINGS);
        long rest = partial.getNetHtg() - 600;

        WithdrawalRequest closed = withdrawalService.closeWithRefund(admin, id, "fonds insuffisants");

        assertThat(closed.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(closed.getRefundedHtg()).isEqualTo(rest);
        assertThat(closed.getPaidHtg()).isEqualTo(600);
        assertThat(closed.getRejectionReason()).isEqualTo("fonds insuffisants");
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(before + rest);

        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }
}
