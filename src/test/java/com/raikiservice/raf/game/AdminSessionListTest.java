package com.raikiservice.raf.game;

import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.game.service.AdminGameService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AdminGameSessionResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parties d'un quiz dans le back-office : recherche, statut et tris. */
class AdminSessionListTest extends IntegrationTest {

    @Autowired private GameService gameService;
    @Autowired private AdminGameService adminGameService;

    private List<Long> list(Long quizId, String query, GameSessionStatus status, Sort sort) {
        return adminGameService.sessions(quizId, false, query, status, PageRequest.of(0, 50, sort))
                .getContent().stream().map(AdminGameSessionResponse::id).toList();
    }

    @Test
    void searchesThePlayerAndSortsEveryColumn() {
        Long category = data.categoryWithQuestions(50);
        User alice = data.player();
        User bob = data.player();
        Long quizId = data.runningQuiz(category, 5, 1000, 1, alice, bob);
        GameStartResponse a = gameService.start(alice.getId(), quizId, "10.0.0.1", "junit");
        GameStartResponse b = gameService.start(bob.getId(), quizId, "10.0.0.2", "junit");

        assertThat(list(quizId, null, null, Sort.unsorted())).containsExactlyInAnyOrder(a.sessionId(), b.sessionId());
        assertThat(list(quizId, alice.getEmail(), null, Sort.unsorted())).containsExactly(a.sessionId());
        assertThat(list(quizId, "10.0.0.2", null, Sort.unsorted())).containsExactly(b.sessionId());
        assertThat(list(quizId, "#" + b.sessionId(), null, Sort.unsorted())).containsExactly(b.sessionId());
        assertThat(list(quizId, null, GameSessionStatus.IN_PROGRESS, Sort.unsorted())).hasSize(2);
        assertThat(list(quizId, null, GameSessionStatus.SUBMITTED, Sort.unsorted())).isEmpty();

        for (String field : List.of("startedAt", "player", "quiz", "score", "status")) {
            for (Sort.Direction direction : Sort.Direction.values()) {
                assertThat(list(quizId, null, null, Sort.by(direction, field))).as(field).hasSize(2);
            }
        }
        assertThat(adminGameService.actionableFlagged(alice.getEmail(), PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> list(null, null, null, Sort.by("userAgent"))).isInstanceOf(ApiException.class);
    }
}
