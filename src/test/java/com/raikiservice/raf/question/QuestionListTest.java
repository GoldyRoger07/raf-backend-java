package com.raikiservice.raf.question;

import java.util.List;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.QuestionFilter;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.question.web.dto.QuestionResponse;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Liste des questions du back-office : recherche, filtres cumulables et tris. */
class QuestionListTest extends IntegrationTest {

    @Autowired private QuestionService questionService;
    @Autowired private QuestionRepository questionRepository;

    private Long categoryId;
    private List<Long> ids;

    /**
     * Quatre questions publiees : 0 facile jamais servie, 1 difficile reussie a 50 %,
     * 2 moyenne suspecte (20 services, 20 reussites), 3 facile reussie a 10 %, et en
     * brouillon.
     */
    @BeforeEach
    void questions() {
        categoryId = data.categoryWithQuestions(4);
        ids = questionRepository.findAll().stream()
                .filter(q -> q.getCategory().getId().equals(categoryId))
                .map(Question::getId).sorted().toList();
        update(0, Difficulty.EASY, 0, 0, QuestionStatus.PUBLISHED);
        update(1, Difficulty.HARD, 10, 5, QuestionStatus.PUBLISHED);
        update(2, Difficulty.MEDIUM, 20, 20, QuestionStatus.PUBLISHED);
        update(3, Difficulty.EASY, 10, 1, QuestionStatus.DRAFT);
    }

    private void update(int index, Difficulty difficulty, int served, int correct, QuestionStatus status) {
        Question question = questionRepository.findById(ids.get(index)).orElseThrow();
        question.setDifficulty(difficulty);
        question.setTimesServed(served);
        question.setTimesCorrect(correct);
        question.setStatus(status);
        questionRepository.save(question);
    }

    private List<Long> list(QuestionFilter filter, Sort sort) {
        return questionService.list(filter, PageRequest.of(0, 50, sort)).getContent().stream()
                .map(QuestionResponse::id).toList();
    }

    private QuestionFilter inCategory() {
        return new QuestionFilter(null, null, categoryId, null, null, null, null);
    }

    @Test
    void withoutStatusAllStatusesAreListed() {
        assertThat(list(inCategory(), Sort.unsorted())).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void criteriaAreCumulative() {
        QuestionFilter easyPublished = new QuestionFilter(null, QuestionStatus.PUBLISHED, categoryId,
                null, Difficulty.EASY, null, null);
        assertThat(list(easyPublished, Sort.unsorted())).containsExactly(ids.get(0));
    }

    @Test
    void searchCombinesWithFilters() {
        QuestionFilter search = new QuestionFilter("question 1 de", QuestionStatus.PUBLISHED, categoryId,
                null, null, null, null);
        assertThat(list(search, Sort.unsorted())).containsExactly(ids.get(1));

        QuestionFilter wrongStatus = new QuestionFilter("question 1 de", QuestionStatus.DRAFT, categoryId,
                null, null, null, null);
        assertThat(list(wrongStatus, Sort.unsorted())).isEmpty();
    }

    @Test
    void searchTreatsWildcardsAsText() {
        QuestionFilter percent = new QuestionFilter("%", null, categoryId, null, null, null, null);
        assertThat(list(percent, Sort.unsorted())).isEmpty();
    }

    @Test
    void usageFilters() {
        assertThat(list(usage(QuestionFilter.Usage.NEVER_SERVED), Sort.unsorted())).containsExactly(ids.get(0));
        assertThat(list(usage(QuestionFilter.Usage.SERVED), Sort.unsorted()))
                .containsExactlyInAnyOrder(ids.get(1), ids.get(2), ids.get(3));
        assertThat(list(usage(QuestionFilter.Usage.SUSPICIOUS), Sort.unsorted())).containsExactly(ids.get(2));
    }

    private QuestionFilter usage(QuestionFilter.Usage usage) {
        return new QuestionFilter(null, null, categoryId, null, null, null, usage);
    }

    @Test
    void difficultyIsSortedEasyMediumHard() {
        assertThat(list(inCategory(), Sort.by("difficulty")))
                .containsExactly(ids.get(0), ids.get(3), ids.get(2), ids.get(1));
        assertThat(list(inCategory(), Sort.by(Sort.Direction.DESC, "difficulty")))
                .containsExactly(ids.get(1), ids.get(2), ids.get(3), ids.get(0));
    }

    @Test
    void neverServedQuestionsStayLastWhenSortingBySuccessRate() {
        assertThat(list(inCategory(), Sort.by("successRate")))
                .containsExactly(ids.get(3), ids.get(1), ids.get(2), ids.get(0));
        assertThat(list(inCategory(), Sort.by(Sort.Direction.DESC, "successRate")))
                .containsExactly(ids.get(2), ids.get(1), ids.get(3), ids.get(0));
    }

    @Test
    void everyListedColumnCanBeSorted() {
        for (String field : List.of("text", "category", "timesServed", "status", "createdAt")) {
            for (Sort.Direction direction : Sort.Direction.values()) {
                assertThat(list(inCategory(), Sort.by(direction, field))).as(field).hasSize(4);
            }
        }
        assertThat(list(inCategory(), Sort.by(Sort.Direction.DESC, "timesServed")).get(0)).isEqualTo(ids.get(2));
    }

    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> list(inCategory(), Sort.by("textHash")))
                .isInstanceOf(ApiException.class);
    }
}
