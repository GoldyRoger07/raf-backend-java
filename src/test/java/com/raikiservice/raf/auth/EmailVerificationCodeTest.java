package com.raikiservice.raf.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.auth.service.AccountEmailService;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.auth.service.VerificationService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestInfrastructure.CapturingMailSender.Mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email de verification : un lien et un code a 6 chiffres. L'un ou l'autre confirme
 * l'adresse ; le premier utilise rend l'autre sans objet.
 */
class EmailVerificationCodeTest extends IntegrationTest {

    private static final String PASSWORD = "MotDePasse123";
    private static final Pattern LINK = Pattern.compile("verification-email\\?token=([^\"&<\\s]+)");
    private static final Pattern CODE = Pattern.compile("letter-spacing:8px[^>]*>(\\d{6})<");

    @Autowired private AuthService authService;
    @Autowired private VerificationService verificationService;
    @Autowired private AccountEmailService accountEmailService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mvc;

    @Test
    void theCodeConfirmsTheAddressAndTheLinkThenSaysItIsDone() throws Exception {
        User player = unverifiedPlayer();
        verificationService.sendEmailVerification(player);
        Mail mail = lastMail(player.getEmail(), 1);

        String bearer = "Bearer " + jwtService.generateAccessToken(reload(player));
        mvc.perform(post("/api/auth/email/verify-code").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code(mail) + "\"}"))
                .andExpect(status().isOk());

        assertThat(reload(player).isEmailVerified()).isTrue();
        // Le lien du meme email ne fait plus rien, et l'app le presente comme reussi.
        assertApiError(ErrorCode.EMAIL_ALREADY_VERIFIED, () -> authService.verifyEmail(token(mail)));
    }

    @Test
    void theLinkStillWorksAndThenTheCodeIsUseless() throws Exception {
        User player = unverifiedPlayer();
        verificationService.sendEmailVerification(player);
        Mail mail = lastMail(player.getEmail(), 1);

        authService.verifyEmail(token(mail));

        assertThat(reload(player).isEmailVerified()).isTrue();
        assertApiError(ErrorCode.EMAIL_ALREADY_VERIFIED,
                () -> authService.verifyEmailCode(player.getId(), code(mail)));
    }

    @Test
    void theCodeEndpointNeedsALoggedInPlayer() throws Exception {
        mvc.perform(post("/api/auth/email/verify-code")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCodesAreCappedAndANewEmailGivesANewCode() throws Exception {
        User player = unverifiedPlayer();
        verificationService.sendEmailVerification(player);
        String first = code(lastMail(player.getEmail(), 1));
        String wrong = first.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertApiError(ErrorCode.OTP_INVALID, () -> authService.verifyEmailCode(player.getId(), wrong));
        }
        // Plafond atteint : meme le bon code est refuse.
        assertApiError(ErrorCode.OTP_TOO_MANY_ATTEMPTS, () -> authService.verifyEmailCode(player.getId(), first));

        verificationService.sendEmailVerification(reload(player));
        Mail second = lastMail(player.getEmail(), 2);
        authService.verifyEmailCode(player.getId(), code(second));
        assertThat(reload(player).isEmailVerified()).isTrue();
    }

    @Test
    void correctingTheAddressCancelsTheOldCode() throws Exception {
        User player = unverifiedPlayer();
        verificationService.sendEmailVerification(player);
        String oldCode = code(lastMail(player.getEmail(), 1));

        String fixed = "corrige-" + player.getEmail();
        accountEmailService.correctOwnEmail(player.getId(), PASSWORD, fixed, "1.2.3.4");
        String newCode = code(lastMail(fixed, 1));

        if (!oldCode.equals(newCode)) {
            assertApiError(ErrorCode.OTP_INVALID, () -> authService.verifyEmailCode(player.getId(), oldCode));
        }
        authService.verifyEmailCode(player.getId(), newCode);
        assertThat(reload(player).isEmailVerified()).isTrue();
    }

    // --- Outils -------------------------------------------------------------------

    private User unverifiedPlayer() {
        User player = data.player();
        player.setEmailVerifiedAt(null);
        player.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(player);
    }

    private User reload(User player) {
        return userRepository.findById(player.getId()).orElseThrow();
    }

    /** Le {@code count}-ieme email de verification recu a cette adresse (envoye apres le commit). */
    private Mail lastMail(String email, int count) throws InterruptedException {
        eventually(() -> assertThat(verificationMails(email)).hasSizeGreaterThanOrEqualTo(count));
        List<Mail> sent = verificationMails(email);
        return sent.get(count - 1);
    }

    private List<Mail> verificationMails(String email) {
        return mails.to(email).stream().filter(mail -> mail.subject().contains("Confirmez")).toList();
    }

    private static String code(Mail mail) {
        Matcher matcher = CODE.matcher(mail.html());
        assertThat(matcher.find()).as("code dans l'email").isTrue();
        return matcher.group(1);
    }

    private static String token(Mail mail) {
        Matcher matcher = LINK.matcher(mail.html());
        assertThat(matcher.find()).as("lien dans l'email").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
