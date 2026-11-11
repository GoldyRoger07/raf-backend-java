package com.raikiservice.raf.game;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.AdminWithdrawalFilter;
import com.raikiservice.raf.wallet.service.WithdrawalService;
import com.raikiservice.raf.wallet.web.dto.AdminWithdrawalResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comptes lies par un appareil commun : la limite solo s'applique au groupe, et le
 * back-office voit les liens (fiche joueur, file des retraits).
 */
class DeviceLinkTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";

    @Autowired private GameService gameService;
    @Autowired private DeviceLinkService deviceLinkService;
    @Autowired private WithdrawalService withdrawalService;

    private static String device() {
        return "web-" + UUID.randomUUID();
    }

    @Test
    void clearingTheBrowserBetweenAccountsDoesNotEscapeTheSoloLimit() {
        Long categoryId = data.categoryWithQuestions(30);
        Long quizId = data.soloQuiz(categoryId, 3, 0);
        Long otherSubject = data.soloQuiz(data.categoryWithQuestions(30), 3, 0);
        User first = data.player();
        User second = data.player();
        User third = data.player();
        String before = device();
        String after = device();

        // Premier compte sur l'appareil ; le second y passe, puis le navigateur est vide
        // (nouvel identifiant) : le second s'y reconnecte, le troisieme s'y cree.
        gameService.start(first.getId(), quizId, IP, UA, before);
        deviceLinkService.seen(second.getId(), before);
        deviceLinkService.seen(second.getId(), after);
        deviceLinkService.seen(third.getId(), after);

        assertApiError(ErrorCode.SOLO_LINKED_ACCOUNT_ALREADY_PLAYED_TODAY,
                () -> gameService.start(third.getId(), quizId, IP, UA, after));
        // Sans en-tete (ancienne version de l'app) : les appareils deja vus suffisent.
        assertApiError(ErrorCode.SOLO_LINKED_ACCOUNT_ALREADY_PLAYED_TODAY,
                () -> gameService.start(second.getId(), quizId, IP, UA, null));

        // Un autre sujet reste permis, comme un compte sans lien.
        gameService.start(third.getId(), otherSubject, IP, UA, after);
        gameService.start(data.player().getId(), quizId, IP, UA, device());
    }

    @Test
    void theGroupStopsAfterTwoLinks() {
        Long categoryId = data.categoryWithQuestions(30);
        Long quizId = data.soloQuiz(categoryId, 3, 0);
        User a = data.player();
        User b = data.player();
        User c = data.player();
        User d = data.player();
        String ab = device();
        String bc = device();
        String cd = device();
        deviceLinkService.seen(b.getId(), ab);
        deviceLinkService.seen(b.getId(), bc);
        deviceLinkService.seen(c.getId(), bc);
        deviceLinkService.seen(c.getId(), cd);
        deviceLinkService.seen(d.getId(), cd);

        gameService.start(a.getId(), quizId, IP, UA, ab);

        // c est a deux liens de a ; d, a trois : un cybercafe n'est pas un seul joueur.
        assertApiError(ErrorCode.SOLO_LINKED_ACCOUNT_ALREADY_PLAYED_TODAY,
                () -> gameService.start(c.getId(), quizId, IP, UA, bc));
        gameService.start(d.getId(), quizId, IP, UA, cd);
    }

    @Test
    void theBackOfficeListsLinkedAccounts() {
        User player = data.player();
        User sibling = data.player();
        User unrelated = data.player();
        String shared = device();
        String second = device();
        deviceLinkService.seen(player.getId(), shared);
        deviceLinkService.seen(player.getId(), second);
        deviceLinkService.seen(sibling.getId(), shared);
        deviceLinkService.seen(sibling.getId(), second);
        deviceLinkService.seen(unrelated.getId(), device());

        assertThat(deviceLinkService.linkedAccounts(player.getId()))
                .singleElement()
                .satisfies(link -> {
                    assertThat(link.playerId()).isEqualTo(sibling.getId());
                    assertThat(link.sharedDevices()).isEqualTo(2);
                });
        assertThat(deviceLinkService.linkedAccounts(unrelated.getId())).isEmpty();
    }

    @Test
    void theWithdrawalQueueFlagsLinkedAccountsAndSharedNumbers() {
        User owner = data.player();
        User other = withWinnings(data.player());
        User clean = withWinnings(data.player());
        deviceLinkService.seen(owner.getId(), device());
        String shared = device();
        deviceLinkService.seen(owner.getId(), shared);
        deviceLinkService.seen(other.getId(), shared);

        // Le second compte retire vers le telephone du premier.
        Long flagged = request(other, owner.getPhone());
        Long fine = request(clean, "+5093" + String.format("%07d", Math.abs(clean.getId() % 10_000_000)));

        AdminWithdrawalResponse flaggedRow = row(flagged);
        assertThat(flaggedRow.linkedAccounts()).isEqualTo(1);
        assertThat(flaggedRow.destinationUsedBy())
                .singleElement()
                .satisfies(account -> assertThat(account.playerId()).isEqualTo(owner.getId()));

        AdminWithdrawalResponse fineRow = row(fine);
        assertThat(fineRow.linkedAccounts()).isZero();
        assertThat(fineRow.destinationUsedBy()).isEmpty();
    }

    private User withWinnings(User player) {
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        return player;
    }

    private Long request(User player, String destination) {
        withdrawalService.sendWithdrawalOtp(player.getId());
        return withdrawalService.request(player.getId(), 1000, PaymentProvider.MONCASH, destination,
                sms.lastCode(player.getPhone())).getId();
    }

    private AdminWithdrawalResponse row(Long withdrawalId) {
        List<AdminWithdrawalResponse> rows = withdrawalService.adminView(WithdrawalStatus.PENDING, null,
                new AdminWithdrawalFilter("#" + withdrawalId, null, null, null, null),
                PageRequest.of(0, 20, Sort.unsorted())).getContent();
        return rows.stream().filter(r -> r.withdrawal().id().equals(withdrawalId)).findFirst().orElseThrow();
    }
}
