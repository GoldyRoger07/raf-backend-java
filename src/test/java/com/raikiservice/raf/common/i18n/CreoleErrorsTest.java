package com.raikiservice.raf.common.i18n;

import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Messages d'erreur et de confirmation en kreyol quand le client envoie Accept-Language: ht. */
class CreoleErrorsTest extends IntegrationTest {

    private static final String BAD_LOGIN = "{\"email\":\"personne@raf.test\",\"password\":\"faux-mot-de-passe\"}";

    @Autowired private MockMvc mvc;
    @Autowired private MessageTranslator translator;

    @Test
    void anApiErrorIsInCreoleWhenAsked() throws Exception {
        mvc.perform(post("/api/auth/login").header("Accept-Language", "ht")
                        .contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Imèl la oswa modpas la pa bon."));
    }

    @Test
    void frenchStaysTheDefault() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN))
                .andExpect(jsonPath("$.message").value("Email ou mot de passe incorrect."));
        mvc.perform(post("/api/auth/login").header("Accept-Language", "fr-FR,fr;q=0.9,ht;q=0.5")
                        .contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN))
                .andExpect(jsonPath("$.message").value("Email ou mot de passe incorrect."));
    }

    @Test
    void validationAndSecurityErrorsAreTranslatedToo() throws Exception {
        mvc.perform(post("/api/auth/login").header("Accept-Language", "ht")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enfòmasyon ou voye yo pa bon."))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Ou dwe mete imèl ou."));

        mvc.perform(get("/api/me").header("Accept-Language", "ht"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Ou dwe konekte."));
    }

    @Test
    void confirmationMessagesAreTranslatedToo() throws Exception {
        mvc.perform(post("/api/auth/password/forgot").header("Accept-Language", "ht")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"personne@raf.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Si gen yon kont ak adrès sa a, nou fèk voye yon lyen."));
    }

    @Test
    void variablePartsAreKeptAndUnknownMessagesStayFrench() {
        assertThat(translator.toCreole("Patientez 60 secondes avant de demander un nouveau code."))
                .isEqualTo("Tann 60 segonn anvan ou mande yon lòt kòd.");
        assertThat(translator.toCreole("Plafond journalier de 5 000 HTG : il reste 1 000 HTG."))
                .isEqualTo("Limit pa jou a se 5 000 HTG : ou rete 1 000 HTG.");
        assertThat(translator.toCreole("Quiz introuvable."))
                .isEqualTo("Nou pa jwenn sa ou ap chèche a (quiz).");
        assertThat(translator.toCreole("Message pas encore traduit."))
                .isEqualTo("Message pas encore traduit.");
    }
}
