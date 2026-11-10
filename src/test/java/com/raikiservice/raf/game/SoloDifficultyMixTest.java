package com.raikiservice.raf.game;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.quiz.domain.DifficultyMix;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.SoloEconomicsService;
import com.raikiservice.raf.quiz.service.SoloEconomicsService.SoloEconomics;
import com.raikiservice.raf.quiz.web.dto.DifficultyMixDto;
import com.raikiservice.raf.quiz.web.dto.SoloQuizDto;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.raikiservice.raf.question.domain.Difficulty.EASY;
import static com.raikiservice.raf.question.domain.Difficulty.HARD;
import static com.raikiservice.raf.question.domain.Difficulty.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Quiz solo reparti par difficulte : chaque partie compte le nombre de questions
 * voulu par niveau, un niveau a court ressert ses reussites avant d'emprunter a un
 * voisin, et le reglage change les parties suivantes d'un quiz deja ouvert.
 */
class SoloDifficultyMixTest extends IntegrationTest {

    private static final String IP = "127.0.0.1";
    private static final String UA = "junit";
    /** Vivier minimal : 10 fois le nombre de questions par partie. */
    private static final int PER_GAME = 5;

    @Autowired private GameService gameService;
    @Autowired private QuizService quizService;
    @Autowired private SoloEconomicsService soloEconomicsService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void eachGameServesTheRequestedNumberPerLevel() {
        Long categoryId = data.categoryWithQuestions(20, 20, 20);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);
        quizService.setDifficultyMix(null, quizId, new DifficultyMix(20, 40, 40));

        for (int game = 0; game < 3; game++) {
            assertThat(levels(playOnce(data.player(), quizId, 0)))
                    .isEqualTo(Map.of(EASY, 1, MEDIUM, 2, HARD, 2));
        }
    }

    @Test
    void aShortLevelIsCompletedByItsNeighbour() {
        // Une seule difficile : les 4 autres viennent des moyennes, pas des faciles.
        Long categoryId = data.categoryWithQuestions(40, 10, 1);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);
        quizService.setDifficultyMix(null, quizId, new DifficultyMix(0, 0, 100));

        assertThat(levels(playOnce(data.player(), quizId, 0)))
                .isEqualTo(Map.of(EASY, 0, MEDIUM, 4, HARD, 1));
    }

    @Test
    void masteredQuestionsOfTheSameLevelComeBackBeforeAnotherLevel() {
        Long categoryId = data.categoryWithQuestions(30, 0, 25);
        Long quizId = data.soloQuiz(categoryId, PER_GAME, 0);
        quizService.setDifficultyMix(null, quizId, new DifficultyMix(0, 0, 100));
        User player = data.player();

        // 23 difficiles sur 25 deja reussies, de la plus ancienne a la plus recente.
        List<Long> hard = jdbc.queryForList("SELECT p.question_id FROM quiz_question_pool p "
                + "JOIN questions q ON q.id = p.question_id "
                + "WHERE p.quiz_id = ? AND q.difficulty = 'HARD' ORDER BY p.question_id",
                Long.class, quizId);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 23; i++) {
            jdbc.update("INSERT INTO solo_mastered_questions "
                    + "(user_id, category_id, question_id, mastered_at) VALUES (?, ?, ?, ?)",
                    player.getId(), categoryId, hard.get(i), Timestamp.from(base.plusSeconds(i)));
        }

        List<Long> drawn = playOnce(player, quizId, 0);

        // Les 2 jamais reussies, puis les 3 plus anciennes : aucune facile.
        assertThat(drawn).containsExactlyInAnyOrder(
                hard.get(23), hard.get(24), hard.get(0), hard.get(1), hard.get(2));
    }

    @Test
    void theMixIsSavedWithTheQuizAndCanBeChangedOnceOpen() {
        Long categoryId = data.categoryWithQuestions(20, 20, 20);
        Quiz draft = quizService.createSolo(null, new SoloQuizDto("Mix " + System.nanoTime(), null,
                categoryId, null, 0, TestData.SOLO_PRIZE, PER_GAME, TestData.QUESTION_SECONDS, null, null,
                new DifficultyMixDto(60, 40, 0)));
        assertThat(quizService.view(draft.getId()).difficultyMix())
                .isEqualTo(new DifficultyMixDto(60, 40, 0));

        // Copie du brouillon : la repartition suit.
        Long copyId = quizService.duplicate(null, draft.getId()).getId();
        assertThat(quizService.view(copyId).difficultyMix()).isEqualTo(new DifficultyMixDto(60, 40, 0));

        quizService.publish(null, draft.getId(), null);
        assertThat(levels(playOnce(data.player(), draft.getId(), 0)))
                .isEqualTo(Map.of(EASY, 3, MEDIUM, 2, HARD, 0));

        quizService.setDifficultyMix(null, draft.getId(), new DifficultyMix(0, 0, 100));
        assertThat(levels(playOnce(data.player(), draft.getId(), 0)))
                .isEqualTo(Map.of(EASY, 0, MEDIUM, 0, HARD, 5));

        quizService.setDifficultyMix(null, draft.getId(), null);
        assertThat(quizService.view(draft.getId()).difficultyMix()).isNull();
        assertThat(playOnce(data.player(), draft.getId(), 0)).hasSize(PER_GAME);
    }

    @Test
    void theEstimateMultipliesTheLevels() {
        // Jamais servies : reussite a priori de 85 / 70 / 55 %.
        Long categoryId = data.categoryWithQuestions(20, 20, 20);

        SoloEconomics economics = soloEconomicsService.preview(categoryId, 10, 500, PER_GAME,
                new DifficultyMix(20, 40, 40));

        double expected = 0.85 * Math.pow(0.70, 2) * Math.pow(0.55, 2);
        assertThat(economics.estimatedWinRate()).isCloseTo(expected, within(1e-9));
        assertThat(economics.estimatedAccuracy()).isCloseTo(Math.pow(expected, 1.0 / PER_GAME), within(1e-9));
        assertThat(economics.byDifficulty())
                .extracting(SoloEconomicsService.LevelEstimate::servedPerGame)
                .containsExactly(1, 2, 2);

        // Sans repartition : la moyenne du vivier, comme avant.
        SoloEconomics free = soloEconomicsService.preview(categoryId, 10, 500, PER_GAME, null);
        assertThat(free.estimatedWinRate()).isCloseTo(Math.pow((0.85 + 0.70 + 0.55) / 3, PER_GAME), within(1e-9));
        assertThat(free.byDifficulty()).isEmpty();
    }

    // --- Outils -------------------------------------------------------------------

    /** Partie complete, puis datee d'un jour passe pour que le joueur puisse rejouer. */
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

    private Map<Difficulty, Integer> levels(List<Long> questionIds) {
        Map<Difficulty, Integer> counts = new EnumMap<>(Difficulty.class);
        for (Difficulty level : Difficulty.values()) {
            counts.put(level, 0);
        }
        for (Long id : questionIds) {
            String level = jdbc.queryForObject("SELECT difficulty FROM questions WHERE id = ?",
                    String.class, id);
            counts.merge(Difficulty.valueOf(level), 1, Integer::sum);
        }
        return counts;
    }
}
