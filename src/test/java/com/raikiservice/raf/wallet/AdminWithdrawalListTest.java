package com.raikiservice.raf.wallet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.AdminWithdrawalFilter;
import com.raikiservice.raf.wallet.service.WalletService;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** File des retraits du back-office : recherche, filtres cumulables et tris. */
class AdminWithdrawalListTest extends IntegrationTest {

    @Autowired private WithdrawalService withdrawalService;
    @Autowired private WalletService walletService;

    private User cash;
    private User moncash;
    private Long cashId;
    private Long moncashId;

    /** Deux demandes en attente : 2 000 HTG en especes, 1 000 HTG par MonCash. */
    @BeforeEach
    void requests() {
        cash = playerWithWinnings();
        moncash = playerWithWinnings();
        cashId = request(cash, 2000, PaymentProvider.MANUAL, null);
        moncashId = request(moncash, 1000, PaymentProvider.MONCASH, "+50937001122");
    }

    private Long request(User player, long amount, PaymentProvider provider, String destination) {
        withdrawalService.sendWithdrawalOtp(player.getId());
        return withdrawalService.request(player.getId(), amount, provider, destination,
                sms.lastCode(player.getPhone())).getId();
    }

    private User playerWithWinnings() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        return player;
    }

    /** Demandes listees parmi les deux du test (la base en contient d'autres). */
    private List<Long> list(AdminWithdrawalFilter filter, Sort sort) {
        return withdrawalService.adminView(WithdrawalStatus.PENDING, null, filter, PageRequest.of(0, 500, sort))
                .getContent().stream().map(r -> r.withdrawal().id())
                .filter(id -> id.equals(cashId) || id.equals(moncashId)).toList();
    }

    private AdminWithdrawalFilter query(String query) {
        return new AdminWithdrawalFilter(query, null, null, null, null);
    }

    @Test
    void searchesPlayerPhoneEmailAndRequestNumber() {
        assertThat(list(query(cash.getEmail().toUpperCase()), Sort.unsorted())).containsExactly(cashId);
        assertThat(list(query(moncash.getPhone().substring(4)), Sort.unsorted())).containsExactly(moncashId);
        assertThat(list(query("#" + cashId), Sort.unsorted())).containsExactly(cashId);
        assertThat(list(query("37001122"), Sort.unsorted())).containsExactly(moncashId);
    }

    @Test
    void filtersByProviderAndDay() {
        assertThat(list(new AdminWithdrawalFilter(null, PaymentProvider.MONCASH, null, null, null), Sort.unsorted()))
                .containsExactly(moncashId);
        assertThat(list(new AdminWithdrawalFilter(null, null, true, null, null), Sort.unsorted())).isEmpty();

        LocalDate today = LocalDate.now(ZoneId.of("America/Port-au-Prince"));
        assertThat(list(new AdminWithdrawalFilter(null, null, false, today, today), Sort.unsorted()))
                .containsExactlyInAnyOrder(cashId, moncashId);
        assertThat(list(new AdminWithdrawalFilter(null, null, null, today.plusDays(1), null), Sort.unsorted()))
                .isEmpty();
    }

    @Test
    void oldestFirstByDefaultAndSortableByAmount() {
        assertThat(list(AdminWithdrawalFilter.NONE, Sort.unsorted())).containsExactly(cashId, moncashId);
        assertThat(list(AdminWithdrawalFilter.NONE, Sort.by("netHtg"))).containsExactly(moncashId, cashId);
        for (String field : List.of("createdAt", "player", "provider")) {
            assertThat(list(AdminWithdrawalFilter.NONE, Sort.by(Sort.Direction.DESC, field))).as(field).hasSize(2);
        }
    }

    @Test
    void aPlayersHistoryListsEveryStatusAndCanBeFiltered() {
        withdrawalService.reject(data.admin().getId(), cashId, "Test");
        Long second = request(cash, 1000, PaymentProvider.MONCASH, "+50937009988");

        List<Long> all = withdrawalService.adminView(null, cash.getId(), AdminWithdrawalFilter.NONE,
                PageRequest.of(0, 10)).getContent().stream().map(r -> r.withdrawal().id()).toList();
        // Tous statuts, le plus recent d'abord.
        assertThat(all).containsExactly(second, cashId);

        assertThat(withdrawalService.adminView(WithdrawalStatus.REJECTED, cash.getId(), AdminWithdrawalFilter.NONE,
                PageRequest.of(0, 10)).getContent()).extracting(r -> r.withdrawal().id()).containsExactly(cashId);
        assertThat(withdrawalService.adminView(null, cash.getId(),
                new AdminWithdrawalFilter(null, PaymentProvider.MONCASH, null, null, null),
                PageRequest.of(0, 10, Sort.by("netHtg"))).getContent())
                .extracting(r -> r.withdrawal().id()).containsExactly(second);
        assertThat(withdrawalService.adminView(null, cash.getId(), AdminWithdrawalFilter.NONE,
                PageRequest.of(0, 10, Sort.by("status"))).getContent()).hasSize(2);
    }

    @Test
    void walletHistoryCanBeFilteredAndSorted() {
        var page = walletService.adminHistory(moncash.getId(), null, BalanceType.WINNINGS, null, null, null,
                PageRequest.of(0, 50, Sort.by("amount")));
        assertThat(page.getContent()).isNotEmpty()
                .allSatisfy(t -> assertThat(t.getBalanceType()).isEqualTo(BalanceType.WINNINGS));
        assertThat(page.getContent().get(0).getAmount()).isNegative();   // le gel du retrait d'abord
        assertThat(walletService.adminHistory(moncash.getId(), null, BalanceType.CREDIT, null, null, null,
                PageRequest.of(0, 50)).getContent()).isEmpty();
        for (String field : List.of("createdAt", "type", "balanceType", "amount")) {
            assertThat(walletService.adminHistory(moncash.getId(), null, null, null, null, null,
                    PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, field))).getContent()).as(field).isNotEmpty();
        }
        assertThatThrownBy(() -> walletService.adminHistory(moncash.getId(), null, null, null, null, null,
                PageRequest.of(0, 50, Sort.by("idempotencyKey")))).isInstanceOf(ApiException.class);
    }

    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> list(AdminWithdrawalFilter.NONE, Sort.by("payoutId")))
                .isInstanceOf(ApiException.class);
    }
}
