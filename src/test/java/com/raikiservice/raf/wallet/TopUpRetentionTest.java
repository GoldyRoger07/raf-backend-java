package com.raikiservice.raf.wallet;

import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.service.TopUpRetentionService;
import com.raikiservice.raf.wallet.service.TopUpService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retenue de recharge : les gourdes qui ne forment pas un credit passent a la recharge suivante. */
class TopUpRetentionTest extends IntegrationTest {

    @Autowired private TopUpService topUpService;
    @Autowired private TopUpRetentionService retentionService;
    @Autowired private SettingsService settingsService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private String previousRate;

    /** L'exemple de reference suppose 1 credit = 10 HTG. */
    @BeforeEach
    void rate() {
        previousRate = settingsService.getString(SettingKeys.CREDIT_HTG_RATE, "10");
        settingsService.update(null, SettingKeys.CREDIT_HTG_RATE, "10");
    }

    @AfterEach
    void restore() {
        settingsService.update(null, SettingKeys.CREDIT_HTG_RATE, previousRate);
    }

    @Test
    void theRemainderIsKeptAndAddedToTheNextTopUp() throws Exception {
        User player = data.player();

        TopUpRequest first = topUp(player, 15);
        assertThat(first.getCredits()).isEqualTo(1);
        assertThat(first.getRetentionUsedHtg()).isZero();
        assertThat(first.getRetentionKeptHtg()).isEqualTo(5);
        assertThat(retentionService.balance(player.getId())).isEqualTo(5);

        // 17 HTG + 5 HTG de retenue = 22 HTG : 2 credits, 2 HTG gardes.
        TopUpRequest second = topUp(player, 17);
        assertThat(second.getCredits()).isEqualTo(2);
        assertThat(second.getRetentionUsedHtg()).isEqualTo(5);
        assertThat(second.getRetentionKeptHtg()).isEqualTo(2);
        assertThat(second.getHtgRate()).isEqualTo(10);
        assertThat(retentionService.balance(player.getId())).isEqualTo(2);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(3);

        // Le joueur est prevenu de la retenue reprise et gardee.
        eventually(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND body LIKE '%5 HTG de votre retenue%'"
                        + " AND body LIKE '%2 HTG sont gardes%'",
                Long.class, player.getId())).isEqualTo(1));

        // Le Tresor affiche la retenue.
        mvc.perform(get("/api/wallet").header("Authorization", token(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionHtg").value(2))
                .andExpect(jsonPath("$.creditBalance").value(3));

        assertNoFinding(player);
    }

    @Test
    void anAmountTooSmallForACreditGoesEntirelyToTheRetention() {
        User player = data.player();

        TopUpRequest small = topUp(player, 5);
        assertThat(small.getCredits()).isZero();
        assertThat(small.getTransactionId()).isNull();
        assertThat(retentionService.balance(player.getId())).isEqualTo(5);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();

        TopUpRequest next = topUp(player, 5);
        assertThat(next.getCredits()).isEqualTo(1);
        assertThat(retentionService.balance(player.getId())).isZero();
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(1);

        assertNoFinding(player);
    }

    @Test
    void anExactAmountLeavesNoRetention() {
        User player = data.player();
        TopUpRequest exact = topUp(player, 30);
        assertThat(exact.getCredits()).isEqualTo(3);
        assertThat(retentionService.balance(player.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topup_retentions WHERE user_id = ?",
                Long.class, player.getId())).isZero();
    }

    @Test
    void anAdministratorCanCorrectTheRetentionButNotBelowZero() {
        User player = data.player();
        User admin = data.admin();
        topUp(player, 13);

        assertThat(retentionService.adjust(admin.getId(), player.getId(), -3, "Remboursement en especes")).isZero();
        assertApiError(ErrorCode.INVALID_AMOUNT,
                () -> retentionService.adjust(admin.getId(), player.getId(), -1, "Trop"));
        assertThat(retentionService.adjust(admin.getId(), player.getId(), 4, "Erreur de saisie")).isEqualTo(4);

        TopUpRetentionService.View view = retentionService.view(player.getId(), 10);
        assertThat(view.balanceHtg()).isEqualTo(4);
        assertThat(view.entries()).hasSize(3);
        assertThat(view.entries().get(0).reason()).isEqualTo(TopUpRetentionService.Reason.ADJUSTMENT);

        // 4 HTG de retenue + 6 HTG = 1 credit tout juste.
        assertThat(topUp(player, 6).getCredits()).isEqualTo(1);
        assertThat(retentionService.balance(player.getId())).isZero();
        assertNoFinding(player);
    }

    // --- Outils -------------------------------------------------------------------

    private TopUpRequest topUp(User player, long amountHtg) {
        return topUpService.createManual(null, player.getId(), amountHtg, "R-" + UUID.randomUUID(), null);
    }

    private void assertNoFinding(User player) {
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
