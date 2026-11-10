package com.raikiservice.raf.quiz;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.AdminQuizFilter;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Liste des quiz du back-office : recherche, filtres cumulables et tris. */
class AdminQuizListTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private TicketService ticketService;

    private Long categoryId;
    private Long paid;
    private Long free;

    /** Deux quiz en inscription dans un sujet a part : un payant avec deux joueurs, un gratuit sans joueur. */
    @BeforeEach
    void quizzes() {
        categoryId = data.categoryWithQuestions(10);
        // 10 questions : de quoi servir 1 question par partie avec le ratio minimal du vivier.
        paid = data.publishedQuiz(categoryId, 1, 0, 10, null, null);
        free = data.freeQuiz(categoryId, 1, 10, null);
        for (int i = 0; i < 2; i++) {
            User player = data.player();
            data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
            ticketService.purchase(player.getId(), paid, UUID.randomUUID().toString());
        }
    }

    private List<Long> list(AdminQuizFilter filter, Sort sort) {
        return quizService.viewByStatus(QuizStatus.REGISTRATION, GameMode.GROUP, filter, PageRequest.of(0, 50, sort))
                .getContent().stream().map(QuizResponse::id).toList();
    }

    private AdminQuizFilter inCategory(Boolean freeOnly) {
        return new AdminQuizFilter(null, categoryId, freeOnly, null);
    }

    @Test
    void filtersByCategoryAndPrice() {
        assertThat(list(inCategory(null), Sort.unsorted())).containsExactlyInAnyOrder(paid, free);
        assertThat(list(inCategory(true), Sort.unsorted())).containsExactly(free);
        assertThat(list(inCategory(false), Sort.unsorted())).containsExactly(paid);
    }

    @Test
    void searchesTheTitle() {
        String title = quizRepository.findById(free).map(Quiz::getTitle).orElseThrow();
        assertThat(list(new AdminQuizFilter(title.toUpperCase(), categoryId, null, null), Sort.unsorted()))
                .containsExactly(free);
    }

    @Test
    void sortsByRegisteredPlayersAndPrice() {
        assertThat(list(inCategory(null), Sort.by(Sort.Direction.DESC, "players"))).containsExactly(paid, free);
        assertThat(list(inCategory(null), Sort.by("players"))).containsExactly(free, paid);
        assertThat(list(inCategory(null), Sort.by("ticketPrice"))).containsExactly(free, paid);
    }

    @Test
    void everyListedColumnCanBeSorted() {
        for (String field : List.of("title", "category", "deadline", "createdAt")) {
            for (Sort.Direction direction : Sort.Direction.values()) {
                assertThat(list(inCategory(null), Sort.by(direction, field))).as(field).hasSize(2);
            }
        }
    }

    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> list(inCategory(null), Sort.by("slug"))).isInstanceOf(ApiException.class);
    }
}
