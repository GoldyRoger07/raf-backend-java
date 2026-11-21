package com.raikiservice.raf.auth;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestInfrastructure.CapturingMailSender.Mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Connexion du personnel en deux etapes : mot de passe, puis code recu par email. */
class StaffLoginCodeTest extends IntegrationTest {

    private static final String PASSWORD = "MotDePasse123";
    private static final Pattern CODE = Pattern.compile("letter-spacing:8px[^>]*>(\\d{6})<");

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockMvc mvc;

    @Test
    void anAdminGetsNoTokenUntilTheEmailedCodeIsEntered() throws Exception {
        User admin = withPassword(data.admin());

        JsonNode challenge = json(login(admin.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist()));
        String mfaToken = challenge.get("mfaToken").asString();
        String code = code(lastMail(admin.getEmail(), 1));

        // Un code faux ne consomme pas le challenge : le bon code passe ensuite.
        String wrong = code.equals("000000") ? "111111" : "000000";
        verify(mfaToken, wrong).andExpect(status().isBadRequest());

        verify(mfaToken, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // Challenge et code sont a usage unique.
        verify(mfaToken, code).andExpect(status().is4xxClientError());
    }

    @Test
    void aNewLoginCancelsThePreviousCode() throws Exception {
        User moderator = withPassword(data.moderator());

        String firstToken = json(login(moderator.getEmail())).get("mfaToken").asString();
        String firstCode = code(lastMail(moderator.getEmail(), 1));
        String secondToken = json(login(moderator.getEmail())).get("mfaToken").asString();
        String secondCode = code(lastMail(moderator.getEmail(), 2));

        verify(firstToken, firstCode).andExpect(status().is4xxClientError());
        verify(secondToken, secondCode).andExpect(status().isOk());
    }

    @Test
    void aPlayerStillLogsInWithThePasswordAlone() throws Exception {
        User player = withPassword(data.player());

        login(player.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        assertThat(mails.to(player.getEmail())).isEmpty();
    }

    @Test
    void aPlayerChallengeCannotBeForged() throws Exception {
        verify("jeton-invente", "123456").andExpect(status().is4xxClientError());
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions verify(String mfaToken, String code) throws Exception {
        return mvc.perform(post("/api/auth/login/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + code + "\"}"));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Mail lastMail(String email, int expectedCount) throws InterruptedException {
        eventually(() -> assertThat(mails.to(email)).hasSize(expectedCount));
        List<Mail> sent = mails.to(email);
        return sent.get(sent.size() - 1);
    }

    private static String code(Mail mail) {
        Matcher matcher = CODE.matcher(mail.html());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private User withPassword(User user) {
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }
}
