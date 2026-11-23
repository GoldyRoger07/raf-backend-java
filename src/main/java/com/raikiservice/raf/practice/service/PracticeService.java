package com.raikiservice.raf.practice.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.CategoryRepository;
import com.raikiservice.raf.question.repository.QuestionRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entrainement : parties courtes et gratuites, sans gain, pour apprendre a jouer.
 *
 * <ul>
 *   <li>Sujets reserves ({@link Category#isPracticeEnabled()}) : jamais proposes en solo ni
 *       en groupe. La bonne reponse et l'explication sont montrees juste apres chaque
 *       reponse, ce qui ne doit jamais toucher une partie payante.</li>
 *   <li>{@link #QUESTIONS} questions, des plus faciles aux plus difficiles, d'abord celles que
 *       le joueur n'a jamais vues en entrainement.</li>
 *   <li>Pas de chrono impose par le serveur : rien a gagner, rien a proteger.</li>
 *   <li>Limite de parties par jour (heure d'Haiti), {@code app.practice.daily_limit}, pour
 *       que l'XP ne se cultive pas a l'infini. Une partie non terminee se reprend sans
 *       compter de nouveau.</li>
 * </ul>
 *
 * <p>Ni credit, ni gourde : seule l'XP de la progression (ProgressService) en profite.
 */
@Service
@RequiredArgsConstructor
public class PracticeService {

    public static final int QUESTIONS = 5;
    public static final int DEFAULT_DAILY_LIMIT = 3;
    public static final int MAX_DAILY_LIMIT = 50;

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");

    private final JdbcTemplate jdbc;
    private final CategoryRepository categoryRepository;
    private final QuestionRepository questionRepository;
    private final SettingsService settingsService;
    private final Random random = new Random();

    public record Subject(Long id, String name, String imageUrl, long questionCount, long practicedCount) {
    }

    /** Ecran Entrainement : sujets proposes et parties restantes aujourd'hui. */
    public record Overview(int dailyLimit, int playedToday, int remainingToday, Long ongoingSessionId,
            List<Subject> subjects) {
    }

    public record ChoiceView(Long id, String text, String imageUrl) {
    }

    /** Une question ; une fois repondue, la correction l'accompagne. */
    public record QuestionView(int position, Long id, String text, String imageUrl, QuestionType type,
            Difficulty difficulty, List<ChoiceView> choices, Long chosenChoiceId, Boolean correct,
            Long correctChoiceId, String explanation) {
    }

    public record SessionView(Long sessionId, Long categoryId, String categoryName, int total,
            int answered, int correct, boolean finished, List<QuestionView> questions) {
    }

    /** Correction d'une reponse, et la partie a jour. */
    public record AnswerResult(boolean correct, Long correctChoiceId, String explanation, SessionView session) {
    }

    public int dailyLimit() {
        long value = settingsService.getLong(SettingKeys.APP_PRACTICE_DAILY_LIMIT, DEFAULT_DAILY_LIMIT);
        return (int) Math.max(0, Math.min(MAX_DAILY_LIMIT, value));
    }

    @Transactional(readOnly = true)
    public Overview overview(Long userId) {
        int limit = dailyLimit();
        int today = startedToday(userId);
        List<Category> categories = categoryRepository.findByActiveTrueAndPracticeEnabledTrueOrderByNameAsc();
        List<Subject> subjects = new ArrayList<>();
        for (Category category : categories) {
            long published = questionRepository.findByCategoryIdAndStatus(category.getId(),
                    QuestionStatus.PUBLISHED, Pageable.ofSize(1)).getTotalElements();
            if (published < QUESTIONS) {
                continue;
            }
            Long practiced = jdbc.queryForObject("SELECT COUNT(*) FROM practice_sessions "
                    + "WHERE user_id = ? AND category_id = ? AND finished_at IS NOT NULL",
                    Long.class, userId, category.getId());
            subjects.add(new Subject(category.getId(), category.getName(),
                    category.getMedia() == null ? null : category.getMedia().getUrl(), published,
                    practiced == null ? 0 : practiced));
        }
        return new Overview(limit, today, Math.max(0, limit - today), ongoing(userId), subjects);
    }

    /**
     * Lance une partie sur ce sujet, ou reprend celle qui n'est pas finie (aucune partie de
     * plus decomptee). Une partie entamee sur un autre sujet est abandonnee : elle reste
     * decomptee.
     */
    @Transactional
    public SessionView start(Long userId, Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .filter(Category::isActive)
                .filter(Category::isPracticeEnabled)
                .orElseThrow(() -> ApiException.notFound("Sujet"));

        Long ongoing = ongoing(userId);
        if (ongoing != null) {
            Long ongoingCategory = jdbc.queryForObject(
                    "SELECT category_id FROM practice_sessions WHERE id = ?", Long.class, ongoing);
            if (categoryId.equals(ongoingCategory)) {
                return view(userId, ongoing);
            }
            jdbc.update("UPDATE practice_sessions SET finished_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now()), ongoing);
        }

        if (startedToday(userId) >= dailyLimit()) {
            throw new ApiException(ErrorCode.PRACTICE_DAILY_LIMIT,
                    "Tu as fait tes " + dailyLimit() + " entrainements du jour. Reviens demain !");
        }

        List<Long> picked = draw(userId, category.getId());
        if (picked.size() < QUESTIONS) {
            throw new ApiException(ErrorCode.GAME_NOT_PLAYABLE,
                    "Ce sujet n'a pas encore assez de questions pour s'entrainer.");
        }

        Instant now = Instant.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO practice_sessions (user_id, category_id, question_count, started_at) "
                            + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, category.getId());
            ps.setInt(3, picked.size());
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keys);
        long sessionId = keys.getKey().longValue();

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            rows.add(new Object[] {sessionId, i + 1, picked.get(i)});
        }
        jdbc.batchUpdate("INSERT INTO practice_questions (session_id, position, question_id) VALUES (?, ?, ?)",
                rows);
        return view(userId, sessionId);
    }

    @Transactional(readOnly = true)
    public SessionView get(Long userId, Long sessionId) {
        return view(userId, sessionId);
    }

    /**
     * Enregistre la reponse a une question (une seule fois) et renvoie la correction.
     * {@code choiceId} null : temps ecoule, la question compte comme fausse.
     */
    @Transactional
    public AnswerResult answer(Long userId, Long sessionId, Long questionId, Long choiceId) {
        requireOwned(userId, sessionId, true);
        Question question = questionRepository.findWithChoicesByIdIn(List.of(questionId)).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("Question"));
        Integer position = jdbc.query("SELECT position FROM practice_questions "
                        + "WHERE session_id = ? AND question_id = ? AND answered_at IS NULL",
                (rs, row) -> rs.getInt(1), sessionId, questionId).stream().findFirst().orElse(null);
        if (position == null) {
            throw new ApiException(ErrorCode.GAME_INVALID_ANSWER, "Question deja repondue ou hors de cette partie.");
        }
        if (choiceId != null && question.getChoices().stream().noneMatch(c -> c.getId().equals(choiceId))) {
            throw new ApiException(ErrorCode.GAME_INVALID_ANSWER, "Cette reponse n'appartient pas a la question.");
        }
        Long correctId = question.correctChoice().getId();
        boolean correct = correctId.equals(choiceId);
        jdbc.update("UPDATE practice_questions SET choice_id = ?, correct = ?, answered_at = ? "
                + "WHERE session_id = ? AND question_id = ?",
                choiceId, correct, Timestamp.from(Instant.now()), sessionId, questionId);
        jdbc.update("UPDATE practice_sessions SET answered_count = answered_count + 1, "
                + "correct_count = correct_count + ?, "
                + "finished_at = CASE WHEN answered_count >= question_count THEN ? ELSE finished_at END "
                + "WHERE id = ?", correct ? 1 : 0, Timestamp.from(Instant.now()), sessionId);
        return new AnswerResult(correct, correctId, question.getExplanation(), view(userId, sessionId));
    }

    // --- Interne ----------------------------------------------------------------

    /** Partie d'entrainement non finie du joueur, la plus recente. */
    private Long ongoing(Long userId) {
        return jdbc.query("SELECT id FROM practice_sessions WHERE user_id = ? AND finished_at IS NULL "
                + "ORDER BY started_at DESC, id DESC LIMIT 1", (rs, row) -> rs.getLong(1), userId)
                .stream().findFirst().orElse(null);
    }

    /** Parties lancees aujourd'hui, heure d'Haiti. */
    private int startedToday(Long userId) {
        Instant since = LocalDate.now(HAITI).atStartOfDay(HAITI).toInstant();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM practice_sessions WHERE user_id = ? "
                + "AND started_at >= ?", Integer.class, userId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    /**
     * {@link #QUESTIONS} questions publiees du sujet : jamais vues en entrainement d'abord,
     * puis les plus anciennement vues. Ordre de la partie : facile, moyen, difficile.
     */
    private List<Long> draw(Long userId, Long categoryId) {
        List<Question> published = questionRepository
                .findByCategoryIdAndStatus(categoryId, QuestionStatus.PUBLISHED, Pageable.unpaged())
                .getContent();
        Map<Long, Instant> seen = new HashMap<>();
        jdbc.query("SELECT pq.question_id, MAX(ps.started_at) FROM practice_questions pq "
                + "JOIN practice_sessions ps ON ps.id = pq.session_id "
                + "WHERE ps.user_id = ? AND ps.category_id = ? GROUP BY pq.question_id",
                rs -> {
                    seen.put(rs.getLong(1), rs.getTimestamp(2).toInstant());
                }, userId, categoryId);

        List<Question> fresh = new ArrayList<>();
        List<Question> old = new ArrayList<>();
        for (Question question : published) {
            (seen.containsKey(question.getId()) ? old : fresh).add(question);
        }
        Collections.shuffle(fresh, random);
        old.sort(Comparator.comparing(q -> seen.get(q.getId())));

        List<Question> picked = new ArrayList<>(fresh.subList(0, Math.min(QUESTIONS, fresh.size())));
        for (int i = 0; picked.size() < QUESTIONS && i < old.size(); i++) {
            picked.add(old.get(i));
        }
        picked.sort(Comparator.comparing(q -> level(q.getDifficulty())));
        return picked.stream().map(Question::getId).toList();
    }

    private static int level(Difficulty difficulty) {
        return difficulty == null ? 1 : difficulty.ordinal();
    }

    private void requireOwned(Long userId, Long sessionId, boolean mustBeOpen) {
        List<Boolean> open = jdbc.query("SELECT finished_at IS NULL FROM practice_sessions "
                + "WHERE id = ? AND user_id = ?", (rs, row) -> rs.getBoolean(1), sessionId, userId);
        if (open.isEmpty()) {
            throw ApiException.notFound("Entrainement");
        }
        if (mustBeOpen && !open.get(0)) {
            throw new ApiException(ErrorCode.GAME_INVALID_ANSWER, "Cet entrainement est termine.");
        }
    }

    private record Row(int position, Long questionId, Long choiceId, Boolean correct, boolean answered) {
    }

    private SessionView view(Long userId, Long sessionId) {
        requireOwned(userId, sessionId, false);
        Map<String, Object> session = jdbc.queryForMap("SELECT ps.category_id, c.name, ps.question_count, "
                + "ps.answered_count, ps.correct_count, ps.finished_at FROM practice_sessions ps "
                + "JOIN categories c ON c.id = ps.category_id WHERE ps.id = ?", sessionId);
        List<Row> rows = jdbc.query("SELECT position, question_id, choice_id, correct, answered_at IS NOT NULL "
                + "FROM practice_questions WHERE session_id = ? ORDER BY position",
                (rs, row) -> new Row(rs.getInt(1), rs.getLong(2), rs.getObject(3, Long.class),
                        rs.getObject(4, Boolean.class), rs.getBoolean(5)), sessionId);
        Map<Long, Question> questions = questionRepository
                .findWithChoicesByIdIn(rows.stream().map(Row::questionId).toList()).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));

        List<QuestionView> views = new ArrayList<>();
        for (Row row : rows) {
            Question question = questions.get(row.questionId());
            List<QuestionChoice> choices = new ArrayList<>(question.getChoices());
            if (question.getType() != QuestionType.TRUE_FALSE) {
                Collections.shuffle(choices, new Random(sessionId * 1_000_003L + question.getId()));
            }
            views.add(new QuestionView(row.position(), question.getId(), question.getText(),
                    question.getMedia() == null ? null : question.getMedia().getUrl(), question.getType(),
                    question.getDifficulty(),
                    choices.stream().map(c -> new ChoiceView(c.getId(), c.getText(),
                            c.getMedia() == null ? null : c.getMedia().getUrl())).toList(),
                    row.choiceId(), row.answered() ? Boolean.TRUE.equals(row.correct()) : null,
                    row.answered() ? question.correctChoice().getId() : null,
                    row.answered() ? question.getExplanation() : null));
        }
        int total = ((Number) session.get("question_count")).intValue();
        int answered = ((Number) session.get("answered_count")).intValue();
        return new SessionView(sessionId, ((Number) session.get("category_id")).longValue(),
                (String) session.get("name"), total, answered,
                ((Number) session.get("correct_count")).intValue(),
                session.get("finished_at") != null, views);
    }
}
