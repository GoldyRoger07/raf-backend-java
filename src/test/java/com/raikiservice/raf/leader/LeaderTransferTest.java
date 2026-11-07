package com.raikiservice.raf.leader;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.PlayerAppService.PlayerAppConfig;
import com.raikiservice.raf.leader.service.LeaderService;
import com.raikiservice.raf.leader.service.LeaderTransferService;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferPlayer;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferResponse;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recharge d'un joueur par son leader, avec les credits du leader. */
class LeaderTransferTest extends IntegrationTest {

    @Autowired private LeaderService leaderService;
    @Autowired private LeaderTransferService transferService;
    @Autowired private PlayerAppService playerAppService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    /** Le reglage est global : on le remet a sa valeur par defaut pour les autres tests. */
    @AfterEach
    void restore() {
        playerAppService.update(null, new PlayerAppConfig(null, true, null));
    }

    @Test
    void theLeaderMovesCreditsFromHisBalanceToHisPlayer() throws Exception {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 200);

        TransferResponse transfer = transferService.transfer(leader.getId(), player.getId(), 50, key());

        assertThat(transfer.credits()).isEqualTo(50);
        assertThat(transfer.leaderBalanceAfter()).isEqualTo(150);
        assertThat(data.balance(leader.getId(), BalanceType.CREDIT)).isEqualTo(150);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(50);

        // Le joueur apparait en tete de liste avec sa derniere recharge.
        TransferPlayer line = transferService.players(leader.getId(), null, PageRequest.of(0, 20))
                .getContent().get(0);
        assertThat(line.id()).isEqualTo(player.getId());
        assertThat(line.lastTransferCredits()).isEqualTo(50);
        assertThat(transferService.history(leader.getId(), PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(1);

        // Le joueur est prevenu.
        eventually(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND title = 'Recharge recue'",
                Long.class, player.getId())).isEqualTo(1));

        // Les deux mouvements sont rattaches a la recharge : rien a signaler.
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> List.of(leader.getId(), player.getId()).contains(finding.userId()))
                .isEmpty();
    }

    @Test
    void aDoubleSubmissionIsAppliedOnce() {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 100);
        String key = key();

        TransferResponse first = transferService.transfer(leader.getId(), player.getId(), 30, key);
        TransferResponse second = transferService.transfer(leader.getId(), player.getId(), 30, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(data.balance(leader.getId(), BalanceType.CREDIT)).isEqualTo(70);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(30);
    }

    @Test
    void theLeaderCannotSendMoreThanHeHas() {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 20);

        assertApiError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> transferService.transfer(leader.getId(), player.getId(), 30, key()));
        assertThat(data.balance(leader.getId(), BalanceType.CREDIT)).isEqualTo(20);
    }

    @Test
    void onlyHisOwnActivePlayersCanBeRecharged() {
        User leader = leader();
        User stranger = data.player();
        data.credit(leader.getId(), BalanceType.CREDIT, 100);

        assertApiError(ErrorCode.LEADER_TRANSFER_NOT_YOUR_PLAYER,
                () -> transferService.transfer(leader.getId(), stranger.getId(), 20, key()));
        assertThat(transferService.players(leader.getId(), null, PageRequest.of(0, 20)).getContent())
                .extracting(TransferPlayer::id).doesNotContain(stranger.getId());
    }

    @Test
    void theAmountMustStayWithinTheBounds() {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 10_000);

        assertApiError(ErrorCode.LEADER_TRANSFER_AMOUNT_INVALID,
                () -> transferService.transfer(leader.getId(), player.getId(), 5, key()));
        assertApiError(ErrorCode.LEADER_TRANSFER_AMOUNT_INVALID,
                () -> transferService.transfer(leader.getId(), player.getId(), 5001, key()));
    }

    @Test
    void theFeatureCanBeTurnedOff() {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 100);

        playerAppService.update(null, new PlayerAppConfig(null, false, null));

        assertThat(transferService.config(leader.getId()).enabled()).isFalse();
        assertApiError(ErrorCode.LEADER_TRANSFER_DISABLED,
                () -> transferService.transfer(leader.getId(), player.getId(), 20, key()));
    }

    @Test
    void theSearchFindsAPlayerByNameOrPhone() {
        User leader = leader();
        User player = playerOf(leader);
        String phoneEnd = player.getPhone().substring(player.getPhone().length() - 4);

        assertThat(transferService.players(leader.getId(), player.getLastName(), PageRequest.of(0, 20))
                .getContent()).extracting(TransferPlayer::id).contains(player.getId());
        assertThat(transferService.players(leader.getId(), phoneEnd, PageRequest.of(0, 20))
                .getContent()).extracting(TransferPlayer::id).contains(player.getId());
        assertThat(transferService.players(leader.getId(), "introuvable-xyz", PageRequest.of(0, 20))
                .getContent()).isEmpty();
    }

    @Test
    void theEndpointsAreForLeadersOnly() throws Exception {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 100);

        mvc.perform(get("/api/leader/transfers/config").header("Authorization", token(player)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/leader/transfers").header("Authorization", token(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":" + player.getId() + ",\"credits\":40,\"requestKey\":\"" + key() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credits").value(40))
                .andExpect(jsonPath("$.leaderBalanceAfter").value(60));
    }

    @Test
    void theSuperAdminFollowsTheTransfersOfALeader() throws Exception {
        User leader = leader();
        User player = playerOf(leader);
        data.credit(leader.getId(), BalanceType.CREDIT, 100);
        transferService.transfer(leader.getId(), player.getId(), 30, key());
        transferService.transfer(leader.getId(), player.getId(), 20, key());

        assertThat(leaderService.get(leader.getId()).transfersTotalCredits()).isEqualTo(50);
        assertThat(leaderService.get(leader.getId()).transfersLast30DaysCredits()).isEqualTo(50);

        mvc.perform(get("/api/admin/leaders/" + leader.getId() + "/transfers")
                        .header("Authorization", token(data.superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].credits").value(20))
                .andExpect(jsonPath("$.content[0].leaderBalanceAfter").value(50))
                .andExpect(jsonPath("$.content[1].leaderBalanceAfter").value(70));
        mvc.perform(get("/api/admin/leaders/" + leader.getId() + "/transfers")
                        .header("Authorization", token(data.admin())))
                .andExpect(status().isForbidden());
    }

    // --- Outils -------------------------------------------------------------------

    private User leader() {
        User user = data.player();
        leaderService.promote(null, user.getId(), new BigDecimal("5"));
        return user;
    }

    private User playerOf(User leader) {
        User player = data.player();
        leaderService.addPlayer(null, leader.getId(), player.getId());
        return player;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
