package com.raikiservice.raf.wallet;

import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.PlayerAppService.PlayerAppConfig;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;
import com.raikiservice.raf.wallet.service.TopUpRetentionService;
import com.raikiservice.raf.wallet.service.TopUpService;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** Recharge avec les gains : le joueur paie des credits avec son solde de gains en HTG. */
class WinningsConversionTest extends IntegrationTest {

    @Autowired private TopUpService topUpService;
    @Autowired private TopUpRetentionService retentionService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private PlayerAppService playerAppService;
    @Autowired private SettingsService settingsService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private String previousRate;

    /** Les exemples supposent 1 credit = 10 HTG. */
    @BeforeEach
    void rate() {
        previousRate = settingsService.getString(SettingKeys.CREDIT_HTG_RATE, "10");
        settingsService.update(null, SettingKeys.CREDIT_HTG_RATE, "10");
    }

    @AfterEach
    void restore() {
        settingsService.update(null, SettingKeys.CREDIT_HTG_RATE, previousRate);
        playerAppService.update(null, new PlayerAppConfig(null, null, true, null, null, null, null));
    }

    @Test
    void onlyWholeCreditsAreDebitedAndTheRestStaysInTheWinnings() throws Exception {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 125);

        TopUpRequest topUp = topUpService.convertWinnings(player.getId(), 125, key());

        // 125 HTG demandes : 12 credits pour 120 HTG, 5 HTG restent dans les gains.
        assertThat(topUp.getProvider()).isEqualTo(PaymentProvider.WINNINGS);
        assertThat(topUp.getStatus()).isEqualTo(TopUpStatus.CONFIRMED);
        assertThat(topUp.getAmountHtg()).isEqualTo(120);
        assertThat(topUp.getCredits()).isEqualTo(12);
        assertThat(topUp.getRetentionUsedHtg()).isZero();
        assertThat(topUp.getRetentionKeptHtg()).isZero();
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(5);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(12);

        // Visible dans « Mes recharges ».
        assertThat(topUpService.historyForUser(player.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(TopUpRequest::getId).containsExactly(topUp.getId());

        // Le joueur est prevenu.
        eventually(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND title = 'Recharge avec vos gains'",
                Long.class, player.getId())).isEqualTo(1));

        // Debit des gains et credit rattaches a la recharge : rien a signaler.
        assertThat(reconciliationService.run(ReconciliationTrigger.MANUAL, null).findings())
                .filteredOn(finding -> player.getId().equals(finding.userId()))
                .isEmpty();
    }

    @Test
    void theTopUpRetentionIsLeftUntouched() {
        User player = data.player();
        topUpService.createManual(null, player.getId(), 15, null, null);
        assertThat(retentionService.balance(player.getId())).isEqualTo(5);
        data.credit(player.getId(), BalanceType.WINNINGS, 30);

        TopUpRequest topUp = topUpService.convertWinnings(player.getId(), 30, key());

        assertThat(topUp.getCredits()).isEqualTo(3);
        assertThat(retentionService.balance(player.getId())).isEqualTo(5);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(4);
    }

    @Test
    void aDoubleSubmissionIsAppliedOnce() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 100);
        String key = key();

        TopUpRequest first = topUpService.convertWinnings(player.getId(), 40, key);
        TopUpRequest second = topUpService.convertWinnings(player.getId(), 40, key);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(60);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isEqualTo(4);
    }

    @Test
    void lessThanOneCreditIsRefused() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 9);

        assertApiError(ErrorCode.WINNINGS_CONVERSION_TOO_SMALL,
                () -> topUpService.convertWinnings(player.getId(), 9, key()));
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(9);
    }

    @Test
    void thePlayerCannotConvertMoreThanHeHas() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 50);

        assertApiError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> topUpService.convertWinnings(player.getId(), 60, key()));
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(50);
        assertThat(data.balance(player.getId(), BalanceType.CREDIT)).isZero();
    }

    @Test
    void theFeatureCanBeTurnedOff() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 50);

        playerAppService.update(null, new PlayerAppConfig(null, null, false, null, null, null, null));

        assertThat(playerAppService.get().winningsConversionEnabled()).isFalse();
        assertApiError(ErrorCode.WINNINGS_CONVERSION_DISABLED,
                () -> topUpService.convertWinnings(player.getId(), 50, key()));
        assertThat(data.balance(player.getId(), BalanceType.WINNINGS)).isEqualTo(50);
    }

    @Test
    void winningsIsNotAWithdrawalMethod() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 500);

        assertApiError(ErrorCode.WITHDRAWAL_PROVIDER_INVALID,
                () -> withdrawalService.request(player.getId(), 200, PaymentProvider.WINNINGS, null, "123456"));
    }

    @Test
    void theEndpointConvertsAndReturnsBothBalances() throws Exception {
        User player = data.player();
        data.credit(player.getId(), BalanceType.WINNINGS, 75);

        mvc.perform(get("/api/public/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningsConversionEnabled").value(true));

        mvc.perform(post("/api/wallet/conversions").header("Authorization", token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountHtg\":75,\"requestKey\":\"" + key() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.topUp.provider").value("WINNINGS"))
                .andExpect(jsonPath("$.topUp.amountHtg").value(70))
                .andExpect(jsonPath("$.topUp.credits").value(7))
                .andExpect(jsonPath("$.creditBalance").value(7))
                .andExpect(jsonPath("$.winningsBalanceHtg").value(5));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
