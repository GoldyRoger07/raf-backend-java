package com.raikiservice.raf.question;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.QuestionExportService;
import com.raikiservice.raf.question.service.QuestionImportService;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.question.web.dto.ChoiceDto;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Export CSV d'une categorie, au format de l'import : il se reimporte sans perte. */
class QuestionExportTest extends IntegrationTest {

    @Autowired private QuestionExportService exportService;
    @Autowired private QuestionImportService importService;
    @Autowired private QuestionService questionService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    @Test
    void anExportReimportsIdentically() {
        Long source = data.categoryWithQuestions(3);
        // Les cas delicats : separateur, guillemets, retour a la ligne, 6 propositions,
        // vrai/faux, bonne reponse en derniere position, brouillon.
        List<ChoiceDto> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new ChoiceDto("Choix " + i + " ; \"cite\"", i == 5, null));
        }
        questionService.createEntity(null, source, "Enonce ; avec \"guillemets\"\net retour ?",
                QuestionType.SINGLE_CHOICE, Difficulty.HARD, "Ligne 1\nligne 2", null, six);
        questionService.createEntity(null, source, "Vrai ou faux : é è à ?", QuestionType.TRUE_FALSE,
                Difficulty.EASY, null, null,
                List.of(new ChoiceDto("Vrai", false, null), new ChoiceDto("Faux", true, null)));

        QuestionExportService.ExportFile all = exportService.exportCsv(null, source, null);
        assertThat(all.count()).isEqualTo(5);
        String csv = new String(all.content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿question;reponse_a;reponse_b;reponse_c;reponse_d;"
                + "reponse_e;reponse_f;bonne_reponse;difficulte;explication");
        assertThat(exportService.exportCsv(null, source, QuestionStatus.PUBLISHED).count()).isEqualTo(3);

        Long target = data.categoryWithQuestions(0);
        QuestionImportService.ImportReport report = importService.importCsv(null, target,
                new MockMultipartFile("file", all.filename(), "text/csv", all.content()), true);
        assertThat(report.errors()).isEmpty();
        assertThat(report.imported()).isEqualTo(5);

        assertThat(snapshot(target)).containsExactlyElementsOf(snapshot(source).stream()
                // L'export ramene enonces et explications sur une ligne.
                .map(line -> line.replace("\n", " "))
                .toList());
    }

    @Test
    void theAdminEndpointServesAnAttachment() throws Exception {
        Long category = data.categoryWithQuestions(2);
        mvc.perform(get("/api/admin/questions/export").param("categoryId", category.toString())
                        .header("Authorization", token(data.moderator())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=")));

        mvc.perform(get("/api/admin/questions/export").param("categoryId", category.toString())
                        .header("Authorization", token(data.player())))
                .andExpect(status().isForbidden());
    }

    /** Enonce, propositions (bonne marquee *), difficulte, explication : sans les ids. */
    private List<String> snapshot(Long categoryId) {
        return questionRepository.findWithChoicesByCategoryIdOrderByIdAsc(categoryId).stream()
                .sorted(Comparator.comparing(Question::getId))
                .map(question -> question.getText() + " | "
                        + question.getChoices().stream()
                                .map(choice -> (choice.isCorrect() ? "*" : "") + choice.getText())
                                .reduce((a, b) -> a + " / " + b).orElse("")
                        + " | " + question.getDifficulty() + " | "
                        + (question.getExplanation() == null ? "" : question.getExplanation()))
                .toList();
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
