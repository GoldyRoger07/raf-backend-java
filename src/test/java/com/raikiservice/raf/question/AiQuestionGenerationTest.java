package com.raikiservice.raf.question;

import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.question.ai.GeneratedQuestion;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionSource;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.AiGenerationRunRepository;
import com.raikiservice.raf.question.service.QuestionGenerationService;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.question.web.dto.ChoiceDto;
import com.raikiservice.raf.question.web.dto.QuestionResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestInfrastructure.FakeQuestionGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Generation par IA : un moyen de plus d'alimenter la banque, toujours en brouillon. */
class AiQuestionGenerationTest extends IntegrationTest {

    @Autowired private QuestionGenerationService generationService;
    @Autowired private QuestionService questionService;
    @Autowired private FakeQuestionGenerator generator;
    @Autowired private AiGenerationRunRepository runRepository;
    @Autowired private SettingsService settingsService;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void resetGenerator() {
        generator.reset();
    }

    @Test
    void generatedQuestionsAreDraftsMarkedWithTheirModel() {
        Long categoryId = data.categoryWithQuestions(2);
        generator.willReturn(List.of(
                qcm("Quelle ville est la capitale d'Haiti ?", "Port-au-Prince"),
                qcm("En quelle annee Haiti est-elle devenue independante ?", "1804"),
                trueFalse("La Citadelle Laferriere se trouve dans le Nord.", true)));

        QuestionGenerationService.Report report = generationService.generate(
                data.moderator().getId(), categoryId, 3, null, null, "  epoque coloniale  ");

        assertThat(report.created()).isEqualTo(3);
        assertThat(report.rejected()).isZero();
        assertThat(report.questions()).allSatisfy(question -> {
            assertThat(question.status()).isEqualTo(QuestionStatus.DRAFT);
            assertThat(question.source()).isEqualTo(QuestionSource.AI);
            assertThat(question.aiModel()).isEqualTo("fake-model");
            assertThat(question.choices()).filteredOn(QuestionResponse.ChoiceView::correct).hasSize(1);
        });
        assertThat(report.questions()).extracting(QuestionResponse::type)
                .containsExactly(QuestionType.SINGLE_CHOICE, QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE);

        // Le modele recoit le sujet, les consignes nettoyees et les enonces deja en banque.
        assertThat(generator.lastRequest().instructions()).isEqualTo("epoque coloniale");
        assertThat(generator.lastRequest().avoid()).hasSize(2);

        // Les brouillons IA se retrouvent par leur origine.
        assertThat(questionService.list(QuestionStatus.DRAFT, categoryId, QuestionSource.AI, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(3);
        assertThat(questionService.list(QuestionStatus.PUBLISHED, categoryId, QuestionSource.AI, PageRequest.of(0, 10))
                .getTotalElements()).isZero();
    }

    @Test
    void invalidQuestionsAreRejectedAndDuplicatesSkipped() {
        Long categoryId = data.categoryWithQuestions(0);
        questionService.create(null, categoryId, "Qui a fonde la ville du Cap ?", QuestionType.SINGLE_CHOICE,
                Difficulty.MEDIUM, null, null, List.of(
                        new ChoiceDto("A", true, null), new ChoiceDto("B", false, null),
                        new ChoiceDto("C", false, null), new ChoiceDto("D", false, null)));

        generator.willReturn(List.of(
                qcm("Qui a fonde la ville du Cap", "Bertrand d'Ogeron"),
                new GeneratedQuestion("Deux bonnes reponses ?", "SINGLE_CHOICE", "EASY", List.of(
                        new GeneratedQuestion.Choice("A", true), new GeneratedQuestion.Choice("B", true),
                        new GeneratedQuestion.Choice("C", false), new GeneratedQuestion.Choice("D", false)), null),
                new GeneratedQuestion("Propositions en double ?", "SINGLE_CHOICE", "EASY", List.of(
                        new GeneratedQuestion.Choice("Jacmel", true), new GeneratedQuestion.Choice("jacmel", false),
                        new GeneratedQuestion.Choice("Cayes", false), new GeneratedQuestion.Choice("Hinche", false)), null),
                qcm("Quel fleuve est le plus long d'Haiti ?", "L'Artibonite")));

        QuestionGenerationService.Report report = generationService.generate(
                data.admin().getId(), categoryId, 5, null, null, null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.duplicates()).isEqualTo(1);
        // Deux questions fautives, plus le manque : 4 recues sur 5 demandees.
        assertThat(report.rejected()).isEqualTo(3);
        assertThat(report.rejections()).extracting(QuestionGenerationService.Rejection::reason)
                .anyMatch(reason -> reason.contains("une bonne reponse"))
                .anyMatch(reason -> reason.contains("identiques"))
                .anyMatch(reason -> reason.contains("4 question(s) sur 5"));
    }

    @Test
    void theRequestedDifficultyAndTypeWin() {
        Long categoryId = data.categoryWithQuestions(0);
        generator.willReturn(List.of(
                trueFalse("Le drapeau haitien est bleu et rouge.", true),
                qcm("Un QCM alors que des vrai / faux sont demandes ?", "Oui")));

        QuestionGenerationService.Report report = generationService.generate(
                data.admin().getId(), categoryId, 2, Difficulty.HARD, QuestionType.TRUE_FALSE, null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.questions().getFirst().difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(report.questions().getFirst().choices()).extracting(QuestionResponse.ChoiceView::text)
                .containsExactly("Vrai", "Faux");
        assertThat(report.rejections()).extracting(QuestionGenerationService.Rejection::reason)
                .containsExactly("Vrai / faux demande, QCM recu.");
    }

    @Test
    void requestsAreBoundedAndFailuresTraced() {
        Long categoryId = data.categoryWithQuestions(0);
        Long adminId = data.admin().getId();
        User superAdmin = data.superAdmin();

        generator.setConfigured(false);
        assertApiError(ErrorCode.AI_NOT_CONFIGURED,
                () -> generationService.generate(adminId, categoryId, 5, null, null, null));
        generator.setConfigured(true);

        long maxPerRequest = settingsService.getLong(SettingKeys.AI_MAX_QUESTIONS_PER_REQUEST, 30);
        assertApiError(ErrorCode.AI_TOO_MANY_QUESTIONS,
                () -> generationService.generate(adminId, categoryId, (int) maxPerRequest + 1, null, null, null));

        // Un echec du fournisseur est trace : il compte dans le plafond du jour.
        long runsBefore = runRepository.count();
        generator.willFail("Le fournisseur d'IA a repondu 529 : surcharge");
        assertApiError(ErrorCode.AI_GENERATION_FAILED,
                () -> generationService.generate(adminId, categoryId, 5, null, null, null));
        assertThat(runRepository.count()).isEqualTo(runsBefore + 1);
        assertThat(runRepository.findAll()).anyMatch(run -> run.getError() != null && run.getError().contains("529"));

        String key = SettingKeys.AI_MAX_REQUESTS_PER_DAY;
        long original = settingsService.getLong(key, 20);
        try {
            settingsService.updateByAdmin(superAdmin.getId(), key,
                    String.valueOf(generationService.status().requestsToday()));
            assertApiError(ErrorCode.AI_DAILY_LIMIT_REACHED,
                    () -> generationService.generate(adminId, categoryId, 5, null, null, null));
        } finally {
            settingsService.updateByAdmin(superAdmin.getId(), key, String.valueOf(original));
        }
    }

    @Test
    void moderatorsGenerateFromTheBackOfficeButNotPlayers() throws Exception {
        Long categoryId = data.categoryWithQuestions(0);
        generator.willReturn(List.of(qcm("Quel est le plat national haitien ?", "Le diri ak djon djon")));
        String body = "{\"categoryId\":" + categoryId + ",\"count\":1,\"difficulty\":\"EASY\"}";

        mvc.perform(get("/api/admin/questions/ai/status").header("Authorization", token(data.moderator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.provider").value("fake"));

        mvc.perform(post("/api/admin/questions/ai/generate").header("Authorization", token(data.moderator()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.questions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.questions[0].source").value("AI"));

        mvc.perform(post("/api/admin/questions/ai/generate").header("Authorization", token(data.player()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/questions/ai/generate").header("Authorization", token(data.moderator()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":" + categoryId + ",\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static GeneratedQuestion qcm(String text, String answer) {
        return new GeneratedQuestion(text, "SINGLE_CHOICE", "MEDIUM", List.of(
                new GeneratedQuestion.Choice(answer, true),
                new GeneratedQuestion.Choice("Autre reponse 1", false),
                new GeneratedQuestion.Choice("Autre reponse 2", false),
                new GeneratedQuestion.Choice("Autre reponse 3", false)), "Explication de " + answer);
    }

    private static GeneratedQuestion trueFalse(String text, boolean answer) {
        return new GeneratedQuestion(text, "TRUE_FALSE", "EASY", List.of(
                new GeneratedQuestion.Choice("Vrai", answer),
                new GeneratedQuestion.Choice("Faux", !answer)), null);
    }

    private String token(User user) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwtService.generateAccessToken(loaded);
    }
}
