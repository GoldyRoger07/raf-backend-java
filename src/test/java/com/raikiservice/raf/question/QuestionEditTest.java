package com.raikiservice.raf.question;

import java.util.List;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.question.web.dto.ChoiceDto;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Modification d'une question deja publiee. */
class QuestionEditTest extends IntegrationTest {

    @Autowired private QuestionService questionService;
    @Autowired private QuestionRepository questionRepository;

    @Test
    void aPublishedQuestionCanBeEdited() {
        Long categoryId = data.categoryWithQuestions(1);
        Question question = questionRepository.findAll().stream()
                .filter(q -> q.getCategory().getId().equals(categoryId)).findFirst().orElseThrow();

        List<Long> idsBefore = questionRepository.findWithChoicesById(question.getId()).orElseThrow()
                .getChoices().stream().map(QuestionChoice::getId).toList();

        questionService.update(null, question.getId(), categoryId, "Enonce corrige ?",
                QuestionType.SINGLE_CHOICE, Difficulty.HARD, "Nouvelle explication", null,
                List.of(new ChoiceDto("Un", false, null), new ChoiceDto("Deux", true, null),
                        new ChoiceDto("Trois", false, null), new ChoiceDto("Quatre", false, null)));

        Question updated = questionRepository.findWithChoicesById(question.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(QuestionStatus.PUBLISHED);
        assertThat(updated.getText()).isEqualTo("Enonce corrige ?");
        assertThat(updated.getChoices()).extracting(QuestionChoice::getText)
                .containsExactly("Un", "Deux", "Trois", "Quatre");
        assertThat(updated.correctChoice().getText()).isEqualTo("Deux");
        // Memes ids : les parties deja jouees designent toujours les bons choix.
        assertThat(updated.getChoices()).extracting(QuestionChoice::getId).containsExactlyElementsOf(idsBefore);
    }

    @Test
    void choicesCanBeAddedAndRemoved() {
        Long categoryId = data.categoryWithQuestions(1);
        Long id = questionRepository.findAll().stream()
                .filter(q -> q.getCategory().getId().equals(categoryId)).findFirst().orElseThrow().getId();

        questionService.update(null, id, categoryId, "Vrai ou faux ?", QuestionType.TRUE_FALSE,
                Difficulty.MEDIUM, null, null,
                List.of(new ChoiceDto("Vrai", true, null), new ChoiceDto("Faux", false, null)));
        assertThat(questionRepository.findWithChoicesById(id).orElseThrow().getChoices())
                .extracting(QuestionChoice::getText).containsExactly("Vrai", "Faux");

        questionService.update(null, id, categoryId, "Trois reponses ?", QuestionType.SINGLE_CHOICE,
                Difficulty.MEDIUM, null, null,
                List.of(new ChoiceDto("A", false, null), new ChoiceDto("B", false, null),
                        new ChoiceDto("C", true, null)));
        Question updated = questionRepository.findWithChoicesById(id).orElseThrow();
        assertThat(updated.getChoices()).extracting(QuestionChoice::getText).containsExactly("A", "B", "C");
        assertThat(updated.getChoices()).extracting(QuestionChoice::getPosition).containsExactly(1, 2, 3);
    }

    @Test
    void neighborsFollowTheListOrder() {
        Long categoryId = data.categoryWithQuestions(3);
        // La liste affiche les plus recentes d'abord.
        List<Long> listed = questionRepository.findAll().stream()
                .filter(q -> q.getCategory().getId().equals(categoryId))
                .sorted(java.util.Comparator.comparing(Question::getCreatedAt).thenComparing(Question::getId).reversed())
                .map(Question::getId).toList();

        var middle = questionService.neighbors(listed.get(1));
        assertThat(middle.previousId()).isEqualTo(listed.get(0));
        assertThat(middle.nextId()).isEqualTo(listed.get(2));
        assertThat(middle.position()).isEqualTo(2);
        assertThat(middle.total()).isEqualTo(3);

        assertThat(questionService.neighbors(listed.get(0)).previousId()).isNull();
        assertThat(questionService.neighbors(listed.get(2)).nextId()).isNull();

        // Une question archivee sort de la liste des publiees.
        questionService.changeStatus(null, listed.get(1), QuestionStatus.ARCHIVED);
        assertThat(questionService.neighbors(listed.get(0)).nextId()).isEqualTo(listed.get(2));
    }
}
