package com.raikiservice.raf.wallet;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.support.FakeTranzakPayoutGateway;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.repository.WithdrawalRequestRepository;
import com.raikiservice.raf.wallet.service.AutoPayoutService;
import com.raikiservice.raf.wallet.service.WalletService;
import com.raikiservice.raf.wallet.service.WithdrawalService;
import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakSignature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retraits automatiques MonCash / NatCash par les payouts Tranzak (simules). */
class AutoPayoutTest extends IntegrationTest {

    private static final String SECRET = "whsec_test_secret";

    @Autowired private AutoPayoutService autoPayoutService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private WithdrawalRequestRepository withdrawalRequestRepository;
    @Autowired private WalletService walletService;
    @Autowired private SettingsService settingsService;
    @Autowired private FakeTranzakPayoutGateway payouts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private String previousMin;
    private String previousFee;

    @BeforeEach
    void enable() {
        payouts.setConfigured(true);
        previousMin = settingsService.getString(SettingKeys.WITHDRAWAL_MIN_HTG, "1000");
        previousFee = settingsService.getString(SettingKeys.WITHDRAWAL_FEE_HTG, "25");
        settingsService.update(null, SettingKeys.WITHDRAWAL_MIN_HTG, "100");
        settingsService.update(null, SettingKeys.WITHDRAWAL_FEE_HTG, "25");
        settingsService.update(null, SettingKeys.WITHDRAWAL_PAYOUT_FEE_BPS, "350");
    }

    @AfterEach
    void restore() {
        payouts.setConfigured(false);
        settingsService.update(null, SettingKeys.WITHDRAWAL_MIN_HTG, previousMin);
        settingsService.update(null, SettingKeys.WITHDRAWAL_FEE_HTG, previousFee);
    }

    @Test
    void onlyTheGatewayPercentageIsKept() {
        WithdrawalRequest request = requestWithdrawal(data.player(), 1000, PaymentProvider.MONCASH);

        // 966 HTG envoyes : Tranzak debite 966 + 3,5 % = 999,81 HTG, dans les 1 000 HTG retires.
        assertThat(request.isAutomaticPayout()).isTrue();
        assertThat(request.getNetHtg()).isEqualTo(966);
        assertThat(request.getFeeHtg()).isEqualTo(34);
    }

    @Test
    void approvalSendsTheMoneyAndTheWebhookMarksItPaid() throws Exception {
        User player = data.player();
        WithdrawalRequest request = requestWithdrawal(player, 1000, PaymentProvider.NATCASH);

        WithdrawalRequest sending = autoPayoutService.approve(data.admin().getId(), request.getId());

        assertThat(sending.getStatus()).isEqualTo(WithdrawalStatus.SENDING);
        assertThat(sending.getPayoutId()).isNotNull();
        var sent = payouts.request(sending.getPayoutId());
        assertThat(sent.amountHtg()).isEqualTo(966);
        assertThat(sent.network()).isEqualTo("natcash");
        assertThat(sent.receiver()).isEqualTo(player.getPhone().replace("+", ""));
        assertThat(sent.reference()).isEqualTo("RAF-WD-" + request.getId() + "-1");

        String body = webhook("payout.completed", sending, "completed", null);
        sendWebhook(body).andExpect(status().isOk());
        sendWebhook(body).andExpect(status().isOk());

        WithdrawalRequest paid = reload(request);
        assertThat(paid.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(paid.getPayoutReference()).isEqualTo(sending.getPayoutId());
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(payouts.createdCount()).isPositive();
    }

    @Test
    void aFailedPayoutGoesBackToTheAdminWhoCanRetryOrReject() throws Exception {
        User player = data.player();
        WithdrawalRequest request = requestWithdrawal(player, 500, PaymentProvider.MONCASH);
        WithdrawalRequest sending = autoPayoutService.approve(data.admin().getId(), request.getId());

        String failed = webhook("payout.failed", sending, "failed", "receiver_not_enabled");
        sendWebhook(failed).andExpect(status().isOk());

        WithdrawalRequest back = reload(request);
        assertThat(back.getStatus()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(back.getPayoutError()).contains("mis a niveau");

        // Nouvel essai : nouvelle reference, confirme par la verification planifiee.
        WithdrawalRequest retry = autoPayoutService.send(data.admin().getId(), request.getId());
        assertThat(retry.getStatus()).isEqualTo(WithdrawalStatus.SENDING);
        assertThat(retry.getPayoutError()).isNull();
        assertThat(payouts.request(retry.getPayoutId()).reference()).isEqualTo("RAF-WD-" + request.getId() + "-2");
        payouts.settle(retry.getPayoutId(), "failed", "provider_unavailable");
        age(request, Duration.ofMinutes(2));
        autoPayoutService.refreshSending();
        assertThat(reload(request).getStatus()).isEqualTo(WithdrawalStatus.APPROVED);

        // L'admin abandonne : les gains geles sont rendus.
        long before = winnings(player);
        withdrawalService.reject(data.admin().getId(), request.getId(), "Compte MonCash a mettre a niveau");
        assertThat(reload(request).getStatus()).isEqualTo(WithdrawalStatus.REJECTED);
        assertThat(winnings(player)).isEqualTo(before + 500);
    }

    @Test
    void aRefusedAuthorizationLeavesTheWithdrawalApproved() {
        WithdrawalRequest request = requestWithdrawal(data.player(), 800, PaymentProvider.MONCASH);
        payouts.failNextAuthorize(new TranzakException("solde", "insufficient_wallet_balance", 402, null));

        WithdrawalRequest result = autoPayoutService.approve(data.admin().getId(), request.getId());

        assertThat(result.getStatus()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(result.getPayoutError()).contains("Tresorerie");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void aTimeoutNeverSendsTheMoneyTwice() {
        WithdrawalRequest request = requestWithdrawal(data.player(), 600, PaymentProvider.MONCASH);
        int before = payouts.createdCount();
        payouts.timeOutNextSends(2);

        WithdrawalRequest sending = autoPayoutService.approve(data.admin().getId(), request.getId());
        assertThat(sending.getStatus()).isEqualTo(WithdrawalStatus.SENDING);
        assertThat(sending.getPayoutId()).isNull();

        // Pendant l'envoi, l'admin ne peut ni rejeter ni relancer.
        assertApiError(ErrorCode.WITHDRAWAL_NOT_PENDING,
                () -> withdrawalService.reject(data.admin().getId(), request.getId(), "non"));
        assertApiError(ErrorCode.WITHDRAWAL_NOT_APPROVED,
                () -> autoPayoutService.send(data.admin().getId(), request.getId()));

        // La verification retrouve le versement par sa reference.
        String reference = "RAF-WD-" + request.getId() + "-1";
        String payoutId = payouts.byReference(reference).orElseThrow().payoutId();
        payouts.settle(payoutId, "completed", null);
        age(request, Duration.ofMinutes(2));
        autoPayoutService.refreshSending();

        WithdrawalRequest paid = reload(request);
        assertThat(paid.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(paid.getPayoutId()).isEqualTo(payoutId);
        assertThat(payouts.createdCount()).isEqualTo(before + 1);
    }

    @Test
    void aSendWithoutAnyTraceAtTranzakReturnsToTheAdmin() {
        WithdrawalRequest request = requestWithdrawal(data.player(), 700, PaymentProvider.MONCASH);
        withdrawalService.approve(data.admin().getId(), request.getId());
        jdbc.update("UPDATE withdrawal_requests SET status = 'SENDING', payout_attempts = 1, payout_sent_at = ?"
                + " WHERE id = ?", Timestamp.from(Instant.now().minus(Duration.ofMinutes(3))), request.getId());

        autoPayoutService.refreshSending();
        assertThat(reload(request).getStatus()).isEqualTo(WithdrawalStatus.SENDING);

        age(request, Duration.ofMinutes(20));
        autoPayoutService.refreshSending();
        WithdrawalRequest back = reload(request);
        assertThat(back.getStatus()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(back.getPayoutError()).contains("non confirme");
    }

    @Test
    void withoutPayoutsTheManualFlowIsUnchanged() {
        payouts.setConfigured(false);
        WithdrawalRequest request = requestWithdrawal(data.player(), 1000, PaymentProvider.MONCASH);

        assertThat(request.isAutomaticPayout()).isFalse();
        assertThat(request.getFeeHtg()).isEqualTo(25);
        WithdrawalRequest approved = autoPayoutService.approve(data.admin().getId(), request.getId());
        assertThat(approved.getStatus()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(approved.getPayoutAttempts()).isZero();
        assertApiError(ErrorCode.AUTO_PAYOUT_UNAVAILABLE,
                () -> autoPayoutService.send(data.admin().getId(), request.getId()));
    }

    @Test
    void aForgedPayoutWebhookIsRefused() throws Exception {
        WithdrawalRequest request = requestWithdrawal(data.player(), 400, PaymentProvider.MONCASH);
        WithdrawalRequest sending = autoPayoutService.approve(data.admin().getId(), request.getId());
        String body = webhook("payout.completed", sending, "completed", null);

        mvc.perform(post("/api/webhooks/tranzak").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Tranzak-Signature", TranzakSignature.sign("whsec_autre", bytes(body))))
                .andExpect(status().isForbidden());
        assertThat(reload(request).getStatus()).isEqualTo(WithdrawalStatus.SENDING);
    }

    private WithdrawalRequest requestWithdrawal(User player, long amountHtg, PaymentProvider provider) {
        data.credit(player.getId(), BalanceType.WINNINGS, amountHtg);
        withdrawalService.sendWithdrawalOtp(player.getId());
        return withdrawalService.request(player.getId(), amountHtg, provider, player.getPhone(),
                sms.lastCode(player.getPhone()));
    }

    private org.springframework.test.web.servlet.ResultActions sendWebhook(String body) throws Exception {
        return mvc.perform(post("/api/webhooks/tranzak").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Tranzak-Signature", TranzakSignature.sign(SECRET, bytes(body))));
    }

    private static String webhook(String event, WithdrawalRequest request, String status, String failureCode) {
        return """
                {"event":"%s","timestamp":1760366400,"data":{"payout_id":"%s","reference":"%s",\
                "network":"moncash","amount":%d,"fee":33.81,"currency":"HTG","status":"%s",\
                "failure_code":%s,"failure_message":%s,"retryable":true}}""".formatted(event, request.getPayoutId(),
                "RAF-WD-" + request.getId() + "-" + request.getPayoutAttempts(), request.getNetHtg(), status,
                failureCode == null ? "null" : "\"" + failureCode + "\"",
                failureCode == null ? "null" : "\"Echec\"");
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private long winnings(User player) {
        return walletService.requireWallet(player.getId()).getWinningsBalance();
    }

    private WithdrawalRequest reload(WithdrawalRequest request) {
        return withdrawalRequestRepository.findById(request.getId()).orElseThrow();
    }

    /** Fait comme si l'envoi en cours datait de {@code age}. */
    private void age(WithdrawalRequest request, Duration age) {
        jdbc.update("UPDATE withdrawal_requests SET payout_sent_at = ?, payout_checked_at = NULL WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)), request.getId());
    }
}
