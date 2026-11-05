package com.raikiservice.raf.leader;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.raikiservice.raf.admin.service.GameProfitService;
import com.raikiservice.raf.admin.service.GameProfitService.Period;
import com.raikiservice.raf.admin.web.dto.GameProfitResponse;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.auth.web.dto.RegisterRequest;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.leader.service.InviteService;
import com.raikiservice.raf.leader.service.LeaderService;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.InviteResponse;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderPlayer;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.repository.WalletRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Lien d'invitation : rattachement au leader et bonus verse apres verification. */
class LeaderInviteTest extends IntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("verification-email\\?token=([^\"&<\\s]+)");

    @Autowired private AuthService authService;
    @Autowired private InviteService inviteService;
    @Autowired private LeaderService leaderService;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GameProfitService gameProfitService;

    @Test
    void aPlayerInvitedByALeaderIsAttachedAndGetsTheBonusOnceHisEmailIsConfirmed() {
        User leader = leader(50);

        User invited = register(leader.getId());

        LeaderPlayer line = playerOf(leader, invited);
        assertThat(line.viaInvite()).isTrue();
        assertThat(line.signupBonusCredits()).isEqualTo(50);
        assertThat(line.bonusPaidAt()).isNull();
        assertThat(creditBalance(invited)).isZero();

        // Le bonus promis est fige : un changement ulterieur ne vaut que pour la suite.
        leaderService.setSignupBonus(null, leader.getId(), 10);

        verify(invited);

        assertThat(creditBalance(invited)).isEqualTo(50);
        assertThat(playerOf(leader, invited).bonusPaidAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM leader_invite_bonuses WHERE player_id = ?",
                Long.class, invited.getId())).isEqualTo(1);

        // Le mouvement est rattache a son detail : rien a signaler.
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> invited.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void offeredCreditsAreACostInTheGameResult() {
        User leader = leader(30);
        GameProfitResponse before = gameProfitService.profit(Period.LAST_7_DAYS);

        verify(register(leader.getId()));

        GameProfitResponse after = gameProfitService.profit(Period.LAST_7_DAYS);
        long rate = after.htgRate();
        assertThat(after.inviteBonusesCredits() - before.inviteBonusesCredits()).isEqualTo(30);
        assertThat(after.inviteBonusesHtg() - before.inviteBonusesHtg()).isEqualTo(30 * rate);
        assertThat(after.resultHtg() - before.resultHtg()).isEqualTo(-30 * rate);
    }

    @Test
    void theLinkOfAnOrdinaryPlayerIsANormalSignup() {
        User friend = data.player();

        User invited = register(friend.getId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM leader_players WHERE player_id = ?",
                Long.class, invited.getId())).isZero();
        InviteResponse invite = inviteService.inviteFor(friend.getId());
        assertThat(invite.leader()).isFalse();
        assertThat(invite.url()).endsWith("/inscription?parrain=" + friend.getId());
    }

    @Test
    void anUnknownInviterDoesNotBlockTheSignup() {
        User invited = register(Long.MAX_VALUE);
        assertThat(invited.getId()).isNotNull();
    }

    @Test
    void withoutBonusTheInvitedPlayerIsOnlyAttached() {
        User leader = leader(0);

        User invited = register(leader.getId());
        verify(invited);

        assertThat(playerOf(leader, invited).viaInvite()).isTrue();
        assertThat(creditBalance(invited)).isZero();
    }

    @Test
    void theInviteOfALeaderAnnouncesItsBonusAndTheBonusIsBounded() {
        User leader = leader(25);
        InviteResponse invite = inviteService.inviteFor(leader.getId());
        assertThat(invite.leader()).isTrue();
        assertThat(invite.signupBonusCredits()).isEqualTo(25);

        assertApiError(ErrorCode.LEADER_BONUS_INVALID, () -> leaderService.setSignupBonus(null, leader.getId(), -1));
        assertApiError(ErrorCode.LEADER_BONUS_INVALID,
                () -> leaderService.setSignupBonus(null, leader.getId(), 100_001));
    }

    // --- Outils -------------------------------------------------------------------

    private User leader(int bonus) {
        User user = data.player();
        leaderService.promote(null, user.getId(), new BigDecimal("5"));
        leaderService.setSignupBonus(null, user.getId(), bonus);
        return user;
    }

    /** Inscription par le parcours public ; numero en +1 pour ne pas croiser ceux de TestData. */
    private User register(Long inviterId) {
        String email = "invite-" + UUID.randomUUID() + "@raf.test";
        String phone = "+1" + (2_000_000_000L + ThreadLocalRandom.current().nextLong(7_000_000_000L));
        authService.register(new RegisterRequest(email, phone, "MotDePasse123", "Filleul", "Test", inviterId));
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    /** Confirmation de l'email par le vrai lien recu, telephone non verifie. */
    private void verify(User player) {
        User user = userRepository.findById(player.getId()).orElseThrow();
        String[] token = new String[1];
        try {
            eventually(() -> {
                assertThat(mails.to(user.getEmail())).isNotEmpty();
                Matcher link = TOKEN.matcher(mails.to(user.getEmail()).get(0).html());
                assertThat(link.find()).isTrue();
                token[0] = URLDecoder.decode(link.group(1), StandardCharsets.UTF_8);
            });
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        authService.verifyEmail(token[0]);
        assertThat(userRepository.findById(player.getId()).orElseThrow().isPhoneVerified()).isFalse();
    }

    private long creditBalance(User player) {
        return walletRepository.findByUserId(player.getId())
                .map(wallet -> wallet.balanceOf(BalanceType.CREDIT))
                .orElse(0L);
    }

    private LeaderPlayer playerOf(User leader, User player) {
        return leaderService.players(leader.getId(), PageRequest.of(0, 100)).getContent().stream()
                .filter(line -> line.id().equals(player.getId()))
                .findFirst()
                .orElseThrow();
    }
}
