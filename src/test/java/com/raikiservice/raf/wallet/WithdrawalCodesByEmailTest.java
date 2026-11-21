package com.raikiservice.raf.wallet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestInfrastructure.CapturingMailSender.Mail;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.CodeChannel;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.CounterWithdrawalService;
import com.raikiservice.raf.wallet.service.WithdrawalCodeService;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Codes de retrait par email, en secours si les SMS sont indisponibles. */
class WithdrawalCodesByEmailTest extends IntegrationTest {

    private static final Pattern CODE = Pattern.compile("letter-spacing:8px[^>]*>(\\d{4,8})<");

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private WithdrawalCodeService withdrawalCodeService;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private CounterWithdrawalService counterWithdrawalService;

    /** Le reglage est partage par tous les tests : toujours revenir au SMS. */
    @AfterEach
    void backToSms() {
        withdrawalCodeService.update(data.superAdmin().getId(), false);
    }

    @Test
    void offByDefaultTheCodeGoesBySmsAndThePhoneMustBeVerified() {
        assertThat(withdrawalCodeService.byEmail()).isFalse();

        User unverified = playerWithWinnings(false);
        assertApiError(ErrorCode.PHONE_NOT_VERIFIED, () -> withdrawalService.sendWithdrawalOtp(unverified.getId()));

        User player = playerWithWinnings(true);
        assertThat(withdrawalService.sendWithdrawalOtp(player.getId())).isEqualTo(CodeChannel.SMS);
        WithdrawalRequest request = withdrawalService.request(player.getId(), 2000, PaymentProvider.MANUAL, null,
                sms.lastCode(player.getPhone()));
        assertThat(request.getOtpChannel()).isEqualTo(CodeChannel.SMS);
    }

    @Test
    void onlyTheSuperAdminSwitchesAndEveryAdminIsWarnedByEmail() throws Exception {
        User admin = data.admin();
        User superAdmin = data.superAdmin();

        mvc.perform(get("/api/admin/withdrawal-codes").header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byEmail").value(false));
        mvc.perform(put("/api/admin/withdrawal-codes").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"byEmail\":true}"))
                .andExpect(status().isForbidden());
        assertThat(withdrawalCodeService.byEmail()).isFalse();

        mvc.perform(put("/api/admin/withdrawal-codes").header("Authorization", token(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"byEmail\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byEmail").value(true));
        mvc.perform(get("/api/public/withdrawal-codes"))
                .andExpect(jsonPath("$.byEmail").value(true));

        eventually(() -> assertThat(mails.to(admin.getEmail()))
                .anyMatch(mail -> mail.html().contains(superAdmin.getEmail())
                        && mail.html().contains("par email a la place du SMS")));
    }

    @Test
    void byEmailAConfirmedEmailIsEnoughAndTheWithdrawalIsMarked() throws Exception {
        withdrawalCodeService.update(data.superAdmin().getId(), true);
        User player = playerWithWinnings(false);

        int before = mails.to(player.getEmail()).size();
        mvc.perform(post("/api/wallet/withdrawals/send-otp").header("Authorization", token(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.message").value("Code de confirmation envoye par email."));
        assertThat(sms.lastMessage(player.getPhone())).isNull();

        String code = codeFromEmail(player, before);
        WithdrawalRequest request = withdrawalService.request(player.getId(), 2000, PaymentProvider.MANUAL, null, code);
        assertThat(request.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(request.getOtpChannel()).isEqualTo(CodeChannel.EMAIL);
    }

    @Test
    void byEmailAnUnconfirmedEmailBlocksTheWithdrawal() {
        withdrawalCodeService.update(data.superAdmin().getId(), true);
        User player = playerWithWinnings(true);
        player.setEmailVerifiedAt(null);
        userRepository.save(player);

        assertApiError(ErrorCode.EMAIL_NOT_VERIFIED, () -> withdrawalService.sendWithdrawalOtp(player.getId()));
    }

    @Test
    void byEmailCounterCodesGoByEmailToo() throws Exception {
        User agent = data.admin();
        User player = playerWithWinnings(true);
        // Demande faite par SMS, code de remise envoye apres la bascule.
        withdrawalService.sendWithdrawalOtp(player.getId());
        Long cash = withdrawalService.request(player.getId(), 2000, PaymentProvider.MANUAL, null,
                sms.lastCode(player.getPhone())).getId();
        withdrawalService.approve(agent.getId(), cash);

        withdrawalCodeService.update(data.superAdmin().getId(), true);
        String smsBefore = sms.lastMessage(player.getPhone());

        int before = mails.to(player.getEmail()).size();
        assertThat(withdrawalService.sendPickupCode(agent.getId(), cash)).isEqualTo(CodeChannel.EMAIL);
        String pickup = codeFromEmail(player, before);
        assertThat(mails.to(player.getEmail()).stream().filter(m -> m.html().contains(pickup)).findFirst()
                .orElseThrow().html()).contains("1975 HTG");
        assertThat(withdrawalService.markPaid(agent.getId(), cash, null, pickup).getStatus())
                .isEqualTo(WithdrawalStatus.PAID);

        int beforeCounter = mails.to(player.getEmail()).size();
        Long counter = counterWithdrawalService.initiate(agent.getId(), player.getId(), 1000, null).getId();
        String counterCode = codeFromEmail(player, beforeCounter);
        WithdrawalRequest paid = counterWithdrawalService.confirm(agent.getId(), counter, counterCode, null);
        assertThat(paid.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(paid.getOtpChannel()).isEqualTo(CodeChannel.EMAIL);
        assertThat(sms.lastMessage(player.getPhone())).isEqualTo(smsBefore);
    }

    /** Code du premier email « code de retrait » recu apres les {@code before} premiers emails. */
    private String codeFromEmail(User player, int before) throws InterruptedException {
        String[] code = new String[1];
        eventually(() -> {
            var sent = mails.to(player.getEmail());
            Mail mail = sent.subList(Math.min(before, sent.size()), sent.size()).stream()
                    .filter(m -> m.subject().equals("Votre code de retrait RAF"))
                    .findFirst().orElse(null);
            assertThat(mail).isNotNull();
            Matcher matcher = CODE.matcher(mail.html());
            assertThat(matcher.find()).isTrue();
            code[0] = matcher.group(1);
        });
        return code[0];
    }

    private User playerWithWinnings(boolean phoneVerified) {
        User player = data.player(phoneVerified);
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        return player;
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
