package com.raikiservice.raf.auth;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.AccountDeletionService;
import com.raikiservice.raf.auth.web.dto.DeleteAccountRequest;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.repository.WalletRepository;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Suppression du compte par son titulaire : anonymisation et garde-fous. */
class AccountDeletionTest extends IntegrationTest {

    private static final String PASSWORD = "MotDePasse123";

    @Autowired private AccountDeletionService accountDeletionService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private MockMvc mvc;

    @Test
    void theAccountIsAnonymisedAndTheAddressFreed() throws Exception {
        User player = playerWithPassword();
        String email = player.getEmail();
        String phone = player.getPhone();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());

        accountDeletionService.deleteOwnAccount(player.getId(), new DeleteAccountRequest(PASSWORD, false));

        User deleted = userRepository.findById(player.getId()).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getEmail()).doesNotContain(email);
        assertThat(deleted.getPhone()).isNotEqualTo(phone);
        assertThat(deleted.getFirstName() + deleted.getLastName()).doesNotContain(player.getFirstName());
        assertThat(userRepository.existsByEmailIgnoreCase(email)).isFalse();
        assertThat(userRepository.existsByPhone(phone)).isFalse();

        // Plus de connexion possible, et l'ancienne adresse a recu la confirmation.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        eventually(() -> assertThat(mails.to(email))
                .anyMatch(mail -> mail.subject().contains("supprime")));

        assertApiError(ErrorCode.ACCOUNT_DELETED, () ->
                accountDeletionService.deleteOwnAccount(player.getId(), new DeleteAccountRequest(PASSWORD, false)));
    }

    @Test
    void theWrongPasswordIsRefused() {
        User player = playerWithPassword();

        assertApiError(ErrorCode.CURRENT_PASSWORD_INVALID, () ->
                accountDeletionService.deleteOwnAccount(player.getId(), new DeleteAccountRequest("faux", false)));
        assertThat(userRepository.findById(player.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void aBalanceMustBeExplicitlyForfeited() {
        User player = playerWithPassword();
        data.credit(player.getId(), BalanceType.WINNINGS, 250);

        assertApiError(ErrorCode.ACCOUNT_DELETION_BALANCE, () ->
                accountDeletionService.deleteOwnAccount(player.getId(), new DeleteAccountRequest(PASSWORD, false)));

        accountDeletionService.deleteOwnAccount(player.getId(), new DeleteAccountRequest(PASSWORD, true));

        // Le portefeuille reste tel quel : le rapprochement ne bouge pas.
        assertThat(walletRepository.findByUserId(player.getId()).orElseThrow().getWinningsBalance()).isEqualTo(250);
    }

    @Test
    void aWithdrawalInProgressBlocksTheDeletion() {
        User player = playerWithPassword();
        data.credit(player.getId(), BalanceType.WINNINGS, 1500);
        withdrawalService.sendWithdrawalOtp(player.getId());
        withdrawalService.request(player.getId(), 1500, PaymentProvider.MONCASH,
                player.getPhone(), sms.lastCode(player.getPhone()));

        assertApiError(ErrorCode.ACCOUNT_DELETION_WITHDRAWAL_PENDING, () ->
                accountDeletionService.deleteOwnAccount(player.getId(), new DeleteAccountRequest(PASSWORD, true)));
    }

    @Test
    void aStaffAccountCannotDeleteItself() {
        User admin = data.admin();
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(admin);

        assertApiError(ErrorCode.ACCOUNT_DELETION_STAFF, () ->
                accountDeletionService.deleteOwnAccount(admin.getId(), new DeleteAccountRequest(PASSWORD, true)));
    }

    private User playerWithPassword() {
        User player = data.player();
        player.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(player);
    }
}
