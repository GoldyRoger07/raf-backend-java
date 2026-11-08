package com.raikiservice.raf.wallet;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.PlayerAppService.PlayerAppConfig;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.support.FakeTranzakGateway;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;
import com.raikiservice.raf.wallet.repository.TopUpRequestRepository;
import com.raikiservice.raf.wallet.service.OnlineTopUpService;
import com.raikiservice.raf.wallet.service.WalletService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recharge en ligne MonCash / NatCash par la passerelle Tranzak (simulee). */
class OnlineTopUpTest extends IntegrationTest {

    private static final String SECRET = "whsec_test_secret";

    @Autowired private OnlineTopUpService onlineTopUpService;
    @Autowired private TopUpRequestRepository topUpRequestRepository;
    @Autowired private WalletService walletService;
    @Autowired private SettingsService settingsService;
    @Autowired private PlayerAppService playerAppService;
    @Autowired private FakeTranzakGateway gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private String previousRate;

    /** Les exemples supposent 1 credit = 10 HTG et 4,5 % de frais. */
    @BeforeEach
    void settings() {
        previousRate = settingsService.getString(SettingKeys.CREDIT_HTG_RATE, "10");
        settingsService.update(null, SettingKeys.CREDIT_HTG_RATE, "10");
        settingsService.update(null, SettingKeys.TOPUP_ONLINE_FEE_BPS, "450");
    }

    @AfterEach
    void restore() {
        settingsService.update(null, SettingKeys.CREDIT_HTG_RATE, previousRate);
        playerAppService.update(null, new PlayerAppConfig(null, null, null, true));
        gateway.setConfigured(true);
    }

    @Test
    void thePlayerPaysTheGatewayFees() {
        // 524 HTG factures : 4,5 % preleves (23,58 HTG), il reste au moins 500 HTG.
        assertThat(onlineTopUpService.quote(500)).isEqualTo(new OnlineTopUpService.Quote(500, 24, 524));
        assertThat(onlineTopUpService.quote(100)).isEqualTo(new OnlineTopUpService.Quote(100, 5, 105));

        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 500, PaymentProvider.MONCASH);

        assertThat(topUp.getStatus()).isEqualTo(TopUpStatus.PENDING);
        assertThat(topUp.getAmountHtg()).isEqualTo(500);
        assertThat(topUp.getFeeHtg()).isEqualTo(24);
        assertThat(topUp.getChargedHtg()).isEqualTo(524);
        assertThat(topUp.getPaymentUrl()).startsWith("https://pay.tranzak.test/");
        var sent = gateway.request(topUp.getProviderRef());
        assertThat(sent.amountHtg()).isEqualTo(524);
        assertThat(sent.method()).isEqualTo("moncash");
        assertThat(sent.reference()).isEqualTo("RAF-TOPUP-" + topUp.getId());
    }

    @Test
    void aSignedWebhookCreditsTheAmountOnceOnly() throws Exception {
        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 500, PaymentProvider.NATCASH);
        String body = webhook("payment.success", topUp, "completed", "524.00");

        sendWebhook(body, TranzakSignature.sign(SECRET, bytes(body))).andExpect(status().isOk());
        // Tranzak renvoie le meme evenement : rien de plus n'est credite.
        sendWebhook(body, TranzakSignature.sign(SECRET, bytes(body))).andExpect(status().isOk());

        TopUpRequest confirmed = reload(topUp);
        assertThat(confirmed.getStatus()).isEqualTo(TopUpStatus.CONFIRMED);
        assertThat(confirmed.getCredits()).isEqualTo(50);
        assertThat(credits(player)).isEqualTo(50);
        assertThat(creditTransactions(topUp)).isEqualTo(1);
    }

    @Test
    void aWebhookWithoutAValidSignatureIsRefused() throws Exception {
        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 500, PaymentProvider.MONCASH);
        String body = webhook("payment.success", topUp, "completed", "524.00");

        sendWebhook(body, null).andExpect(status().isForbidden());
        sendWebhook(body, TranzakSignature.sign("whsec_autre", bytes(body))).andExpect(status().isForbidden());
        // Corps modifie apres signature.
        String signed = TranzakSignature.sign(SECRET, bytes(body));
        sendWebhook(body.replace("524.00", "5240.00"), signed).andExpect(status().isForbidden());

        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.PENDING);
        assertThat(credits(player)).isZero();
    }

    @Test
    void aFailedPaymentIsMarkedFailedAndALatePaymentIsStillCredited() throws Exception {
        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 200, PaymentProvider.MONCASH);

        String failed = webhook("payment.failed", topUp, "failed", "210.00");
        sendWebhook(failed, TranzakSignature.sign(SECRET, bytes(failed))).andExpect(status().isOk());
        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.FAILED);

        // Le paiement finit par passer : l'argent est arrive, le joueur est credite.
        String success = webhook("payment.success", topUp, "completed", "210.00");
        sendWebhook(success, TranzakSignature.sign(SECRET, bytes(success))).andExpect(status().isOk());
        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.CONFIRMED);
        assertThat(credits(player)).isEqualTo(20);
    }

    @Test
    void anUnexpectedAmountIsNotCredited() throws Exception {
        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 500, PaymentProvider.MONCASH);

        String body = webhook("payment.success", topUp, "completed", "52.40");
        sendWebhook(body, TranzakSignature.sign(SECRET, bytes(body))).andExpect(status().isOk());

        TopUpRequest still = reload(topUp);
        assertThat(still.getStatus()).isEqualTo(TopUpStatus.PENDING);
        assertThat(still.getNote()).contains("Montant recu inattendu");
        assertThat(credits(player)).isZero();
    }

    @Test
    void theScheduledCheckConfirmsAPaymentWhoseWebhookWasLost() {
        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 300, PaymentProvider.MONCASH);
        gateway.settle(topUp.getProviderRef(), "completed");

        // Trop recente : la verification planifiee attend une minute.
        onlineTopUpService.refreshPending();
        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.PENDING);

        age(topUp, Duration.ofMinutes(2));
        onlineTopUpService.refreshPending();
        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.CONFIRMED);
        assertThat(credits(player)).isEqualTo(30);
    }

    @Test
    void anAbandonedPaymentExpires() {
        User player = data.player();
        TopUpRequest topUp = onlineTopUpService.start(player.getId(), 300, PaymentProvider.NATCASH);

        age(topUp, Duration.ofMinutes(10));
        onlineTopUpService.refreshPending();
        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.PENDING);

        age(topUp, Duration.ofHours(1));
        onlineTopUpService.refreshPending();
        assertThat(reload(topUp).getStatus()).isEqualTo(TopUpStatus.EXPIRED);
    }

    @Test
    void aPaymentCreatedDespiteATimeoutIsFoundByItsReference() {
        User player = data.player();
        gateway.timeOutNextCreateAfterCreating();

        assertApiError(ErrorCode.ONLINE_TOPUP_UNAVAILABLE,
                () -> onlineTopUpService.start(player.getId(), 400, PaymentProvider.MONCASH));

        TopUpRequest orphan = topUpRequestRepository.findByUserIdOrderByCreatedAtDesc(player.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().getFirst();
        assertThat(orphan.getStatus()).isEqualTo(TopUpStatus.PENDING);
        assertThat(orphan.getProviderRef()).isNull();

        // Le joueur avait tout de meme paye : la reference retrouve le paiement.
        String transactionId = gateway.findByReference("RAF-TOPUP-" + orphan.getId()).orElseThrow().transactionId();
        gateway.settle(transactionId, "completed");
        age(orphan, Duration.ofMinutes(2));
        onlineTopUpService.refreshPending();

        TopUpRequest confirmed = reload(orphan);
        assertThat(confirmed.getStatus()).isEqualTo(TopUpStatus.CONFIRMED);
        assertThat(confirmed.getProviderRef()).isEqualTo(transactionId);
    }

    @Test
    void aRefusedCreationFailsTheTopUp() {
        User player = data.player();
        gateway.failNextCreate(new TranzakException("trop bas", "amount_too_low", 400, null));

        assertApiError(ErrorCode.INVALID_AMOUNT,
                () -> onlineTopUpService.start(player.getId(), 100, PaymentProvider.NATCASH));
        TopUpRequest failed = topUpRequestRepository.findByUserIdOrderByCreatedAtDesc(player.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().getFirst();
        assertThat(failed.getStatus()).isEqualTo(TopUpStatus.FAILED);
    }

    @Test
    void limitsAndSwitches() {
        User player = data.player();

        assertApiError(ErrorCode.INVALID_AMOUNT,
                () -> onlineTopUpService.start(player.getId(), 20, PaymentProvider.MONCASH));
        assertApiError(ErrorCode.VALIDATION_ERROR,
                () -> onlineTopUpService.start(player.getId(), 500, PaymentProvider.MANUAL));

        for (int i = 0; i < 3; i++) {
            onlineTopUpService.start(player.getId(), 100, PaymentProvider.MONCASH);
        }
        assertApiError(ErrorCode.ONLINE_TOPUP_TOO_MANY_PENDING,
                () -> onlineTopUpService.start(player.getId(), 100, PaymentProvider.MONCASH));

        User other = data.player();
        playerAppService.update(null, new PlayerAppConfig(null, null, null, false));
        assertThat(onlineTopUpService.config().enabled()).isFalse();
        assertApiError(ErrorCode.ONLINE_TOPUP_DISABLED,
                () -> onlineTopUpService.start(other.getId(), 100, PaymentProvider.MONCASH));

        playerAppService.update(null, new PlayerAppConfig(null, null, null, true));
        gateway.setConfigured(false);
        assertThat(onlineTopUpService.config().enabled()).isFalse();
    }

    @Test
    void playerEndpoints() throws Exception {
        User player = data.player();
        mvc.perform(get("/api/public/online-topup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.feeBps").value(450));

        String created = mvc.perform(post("/api/wallet/topups/online").header("Authorization", token(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountHtg\":500,\"provider\":\"MONCASH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.feeHtg").value(24))
                .andExpect(jsonPath("$.chargedHtg").value(524))
                .andExpect(jsonPath("$.paymentUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Retour du joueur : la recharge est relue chez Tranzak.
        TopUpRequest topUp = topUpRequestRepository.findById(id).orElseThrow();
        gateway.settle(topUp.getProviderRef(), "completed");
        mvc.perform(get("/api/wallet/topups/" + id).header("Authorization", token(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.credits").value(50))
                .andExpect(jsonPath("$.paymentUrl").doesNotExist());

        mvc.perform(get("/api/wallet/topups/" + id).header("Authorization", token(data.player())))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions sendWebhook(String body, String signature)
            throws Exception {
        var request = post("/api/webhooks/tranzak").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            request.header("X-Tranzak-Signature", signature);
        }
        return mvc.perform(request);
    }

    private static String webhook(String event, TopUpRequest topUp, String status, String amount) {
        return """
                {"event":"%s","timestamp":"2026-11-08T12:00:00Z","data":{"transaction_id":"%s","amount":"%s",\
                "currency":"HTG","status":"%s","payment_method":"moncash","reference":"RAF-TOPUP-%d",\
                "metadata":{"topup_id":%d}}}""".formatted(event, topUp.getProviderRef(), amount, status,
                topUp.getId(), topUp.getId());
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private long credits(User player) {
        walletService.getOrCreate(player.getId());
        return walletService.requireWallet(player.getId()).getCreditBalance();
    }

    private TopUpRequest reload(TopUpRequest topUp) {
        return topUpRequestRepository.findById(topUp.getId()).orElseThrow();
    }

    private long creditTransactions(TopUpRequest topUp) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE reference_type = 'TopUpRequest'"
                + " AND reference_id = ? AND type = 'TOPUP'", Long.class, String.valueOf(topUp.getId()));
    }

    /** Fait comme si la recharge avait ete lancee il y a {@code age}. */
    private void age(TopUpRequest topUp, Duration age) {
        jdbc.update("UPDATE topup_requests SET created_at = ?, checked_at = NULL WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)), topUp.getId());
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
