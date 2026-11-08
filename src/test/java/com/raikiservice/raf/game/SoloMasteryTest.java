package com.raikiservice.raf.game;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.SoloMasteryService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Questions deja reussies en solo : plus servies au joueur sur le sujet, sauf quand
 * le vivier n'en compte plus assez d'autres ; on ressert alors les plus anciennes.
 */
class SoloMasteryTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";
    private static final int PER_GAME = 3;
    /** Vivier minimal accepte : 10 fois le nombre de questions par partie. */
    private static final int POOL = 30;

    @Autowired private GameService gameService;
    @Autowired private SoloMasteryService soloMasteryService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void correctlyAnsweredQuestionsAreNotServedAgain() {
        Long categoryId = data.categoryWithQuestions(POOL);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);
        User player = data.player();

        Set<Long> mastered = new HashSet<>();
        // 9 parties a 2 bonnes reponses sur 3 : 18 questions reussies, jamais resservies.
        for (int game = 0; game < 9; game++) {
            List<Long> drawn = playOnce(player, quizId, 2);
            assertThat(drawn).hasSize(PER_GAME).noneMatch(mastered::contains);
            mastered.addAll(drawn.subList(0, 2));
        }

        assertThat(masteredIds(player.getId(), categoryId)).containsExactlyInAnyOrderElementsOf(mastered);
        assertThat(soloMasteryService.countBySubject(player.getId()))
                .singleElement()
                .satisfies(count -> {
                    assertThat(count.categoryId()).isEqualTo(categoryId);
                    assertThat(count.masteredCount()).isEqualTo(18);
                });
    }

    @Test
    void whenTheListIsFullTheOldestAreServedAgain() {
        Long categoryId = data.categoryWithQuestions(POOL);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);
        User player = data.player();

        // 29 questions sur 30 deja reussies, de la plus ancienne a la plus recente.
        List<Long> pool = jdbc.queryForList(
                "SELECT question_id FROM quiz_question_pool WHERE quiz_id = ? ORDER BY question_id",
                Long.class, quizId);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < POOL - 1; i++) {
            jdbc.update("INSERT INTO solo_mastered_questions "
                    + "(user_id, category_id, question_id, mastered_at) VALUES (?, ?, ?, ?)",
                    player.getId(), categoryId, pool.get(i), Timestamp.from(base.plusSeconds(i)));
        }

        List<Long> drawn = playOnce(player, quizId, 0);

        // La seule jamais reussie, completee par les deux plus anciennes.
        assertThat(drawn).containsExactlyInAnyOrder(pool.get(POOL - 1), pool.get(0), pool.get(1));
        // Purgees et ratees : elles sortent de la liste ; les autres restent.
        assertThat(masteredIds(player.getId(), categoryId))
                .hasSize(POOL - 3)
                .doesNotContain(pool.get(0), pool.get(1), pool.get(POOL - 1));
    }

    @Test
    void aNewSuccessMovesTheQuestionToTheEndOfTheQueue() {
        Long categoryId = data.categoryWithQuestions(POOL);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);
        User player = data.player();

        List<Long> pool = jdbc.queryForList(
                "SELECT question_id FROM quiz_question_pool WHERE quiz_id = ? ORDER BY question_id",
                Long.class, quizId);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < POOL; i++) {
            jdbc.update("INSERT INTO solo_mastered_questions "
                    + "(user_id, category_id, question_id, mastered_at) VALUES (?, ?, ?, ?)",
                    player.getId(), categoryId, pool.get(i), Timestamp.from(base.plusSeconds(i)));
        }

        // Tout est reussi : les 3 plus anciennes reviennent, et sont reussies a nouveau.
        List<Long> first = playOnce(player, quizId, PER_GAME);
        assertThat(first).containsExactlyInAnyOrder(pool.get(0), pool.get(1), pool.get(2));
        assertThat(masteredIds(player.getId(), categoryId)).hasSize(POOL);

        // Elles sont desormais les plus recentes : ce sont les 3 suivantes qui reviennent.
        List<Long> second = playOnce(player, quizId, 0);
        assertThat(second).containsExactlyInAnyOrder(pool.get(3), pool.get(4), pool.get(5));
    }

    @Test
    void testAccountsFeedTheListToo() {
        Long categoryId = data.categoryWithQuestions(POOL);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);

        User tester = data.player();
        tester.setTest(true);
        userRepository.save(tester);
        List<Long> first = playOnce(tester, quizId, PER_GAME);
        assertThat(masteredIds(tester.getId(), categoryId)).containsExactlyInAnyOrderElementsOf(first);

        List<Long> second = playOnce(tester, quizId, PER_GAME);
        assertThat(second).doesNotContainAnyElementsOf(first);
        assertThat(masteredIds(tester.getId(), categoryId)).hasSize(2 * PER_GAME);
    }

    @Test
    void invalidatedGamesDoNotFeedTheList() {
        Long categoryId = data.categoryWithQuestions(POOL);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);

        User player = data.player();
        playOnce(player, quizId, PER_GAME);
        assertThat(masteredIds(player.getId(), categoryId)).hasSize(PER_GAME);

        // Un sans-faute paye ne s'invalide plus : on joue une partie perdue.
        User other = data.player();
        playOnce(other, quizId, 2);
        Long sessionId = jdbc.queryForObject(
                "SELECT id FROM game_sessions WHERE user_id = ?", Long.class, other.getId());
        assertThat(masteredIds(other.getId(), categoryId)).hasSize(2);
        gameService.invalidateByAdmin(data.admin().getId(), sessionId, "test");
        assertThat(masteredIds(other.getId(), categoryId)).isEmpty();
    }

    // --- Outils -------------------------------------------------------------------

    /**
     * Joue une partie complete, {@code correct} premieres reponses justes, et
     * renvoie les questions tirees dans l'ordre de jeu. La partie est ensuite datee
     * d'un jour passe, pour que le joueur puisse rejouer le sujet.
     */
    private List<Long> playOnce(User player, Long quizId, int correct) {
        GameStartResponse game = gameService.start(player.getId(), quizId, IP, UA);
        GameStartResponse.CurrentQuestion current = game.current();
        for (int i = 0; current != null; i++) {
            Long questionId = current.question().id();
            Long choiceId = i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId);
            GameTurnResponse turn = gameService.answer(player.getId(), game.sessionId(),
                    new AnswerQuestionDto(questionId, choiceId));
            current = turn.next();
        }
        jdbc.update("UPDATE game_sessions SET solo_day = DATE_ADD('2000-01-01', INTERVAL id DAY) "
                + "WHERE id = ?", game.sessionId());
        return jdbc.queryForList("SELECT question_id FROM game_session_questions "
                + "WHERE session_id = ? ORDER BY position", Long.class, game.sessionId());
    }

    private List<Long> masteredIds(Long userId, Long categoryId) {
        return jdbc.queryForList("SELECT question_id FROM solo_mastered_questions "
                + "WHERE user_id = ? AND category_id = ?", Long.class, userId, categoryId);
    }
}
