package com.raikiservice.raf.game.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.game.web.dto.SoloLeaderboardResponse;
import com.raikiservice.raf.game.web.dto.SoloLeaderboardResponse.Entry;
import com.raikiservice.raf.quiz.domain.Quiz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classement d'un quiz solo, montre aux joueurs comme preuve sociale.
 *
 * <p>Une ligne par joueur : son meilleur nombre de bonnes reponses sur ce quiz et la
 * somme des prix de ses sans-fautes sur ce quiz. Tri : meilleur score, puis plus gros
 * gain, puis le premier a avoir atteint ce score. Les {@value #TOP} premiers, plus la
 * place du joueur qui regarde s'il n'y est pas.
 *
 * <p>Comptent les parties soumises ; jamais les parties invalidees ou expirees, les
 * comptes ou parties de test, ni le personnel. Les noms sont masques (« Je*** Do*** »).
 */
@Service
@RequiredArgsConstructor
public class SoloLeaderboardService {

    static final int TOP = 50;

    /** Meilleur score et gains par joueur, puis rang ; partage par la liste et le resume. */
    private static final String RANKED = """
            WITH per_player AS (
                SELECT gs.user_id,
                       MAX(gs.correct_count) AS best,
                       COALESCE(SUM(CASE WHEN gs.won THEN gs.prize_htg ELSE 0 END), 0) AS won_htg,
                       SUM(CASE WHEN gs.won THEN 1 ELSE 0 END) AS perfects
                FROM game_sessions gs
                JOIN users u ON u.id = gs.user_id
                WHERE gs.quiz_id = ? AND gs.game_mode = 'SOLO' AND gs.status = 'SUBMITTED'
                  AND gs.started_at >= ?
                  AND gs.is_test = FALSE AND u.is_test = FALSE
                  AND NOT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                  WHERE ur.user_id = u.id AND r.name IN ('ADMIN', 'SUPER_ADMIN', 'MODERATOR'))
                GROUP BY gs.user_id
            ),
            reached AS (
                SELECT p.*,
                       (SELECT MIN(g2.submitted_at) FROM game_sessions g2
                        WHERE g2.user_id = p.user_id AND g2.quiz_id = ? AND g2.game_mode = 'SOLO'
                          AND g2.status = 'SUBMITTED' AND g2.started_at >= ?
                          AND g2.correct_count = p.best) AS reached_at
                FROM per_player p
            ),
            ranked AS (
                SELECT r.*, ROW_NUMBER() OVER (ORDER BY r.best DESC, r.won_htg DESC, r.reached_at ASC,
                                                        r.user_id ASC) AS rk
                FROM reached r
            )
            """;

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public SoloLeaderboardResponse leaderboard(Quiz quiz, Long viewerId) {
        if (!quiz.isSolo()) {
            throw ApiException.notFound("Classement solo");
        }
        Long quizId = quiz.getId();
        // Nombre de questions change : seules les parties lancees depuis comptent.
        Timestamp since = Timestamp.from(quiz.getLeaderboardSince() == null ? Instant.EPOCH : quiz.getLeaderboardSince());

        List<Entry> rows = jdbc.query(RANKED + """
                SELECT ranked.rk, ranked.user_id, ranked.best, ranked.won_htg, ranked.perfects,
                       u.first_name, u.last_name
                FROM ranked JOIN users u ON u.id = ranked.user_id
                WHERE ranked.rk <= ? OR ranked.user_id = ?
                ORDER BY ranked.rk
                """, (rs, row) -> entry(rs, viewerId), quizId, since, quizId, since, TOP, viewerId);

        List<Entry> top = rows.stream().filter(e -> e.rank() <= TOP).toList();
        Entry me = rows.stream().filter(Entry::me).findFirst().orElse(null);

        Totals totals = jdbc.queryForObject(RANKED + """
                SELECT COUNT(*) AS players, COALESCE(SUM(perfects), 0) AS perfects,
                       COALESCE(SUM(won_htg), 0) AS won_htg
                FROM ranked
                """, (rs, row) -> new Totals(rs.getLong("players"), rs.getLong("perfects"), rs.getLong("won_htg")),
                quizId, since, quizId, since);

        return new SoloLeaderboardResponse(quizId, quiz.getTitle(), quiz.getQuestionsPerGame(),
                totals.players(), totals.perfects(), totals.wonHtg(), top, me);
    }

    private record Totals(long players, long perfects, long wonHtg) {
    }

    private static Entry entry(ResultSet rs, Long viewerId) throws SQLException {
        long userId = rs.getLong("user_id");
        return new Entry(rs.getInt("rk"), mask(rs.getString("first_name"), rs.getString("last_name")),
                rs.getInt("best"), rs.getLong("won_htg"), rs.getInt("perfects"), viewerId != null && viewerId == userId);
    }

    /** « Jean-Baptiste Dorvil » donne « Je*** Do*** » : reconnaissable par soi, pas par les autres. */
    static String mask(String firstName, String lastName) {
        String first = part(firstName);
        String last = part(lastName);
        return last.isEmpty() ? first : first.isEmpty() ? last : first + " " + last;
    }

    private static String part(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        // Par points de code : un prenom accentue ou un emoji ne doit pas etre coupe en deux.
        int end = trimmed.offsetByCodePoints(0, Math.min(2, trimmed.codePointCount(0, trimmed.length())));
        return trimmed.substring(0, end) + "***";
    }
}
