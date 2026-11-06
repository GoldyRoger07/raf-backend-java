package com.raikiservice.raf.game.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeSet;

import com.raikiservice.raf.game.web.dto.PlayerStatsResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistiques personnelles d'un joueur, calculees sur ses seules parties soumises
 * (une partie expiree ou invalidee ne compte pas).
 */
@Service
@RequiredArgsConstructor
public class PlayerStatsService {

    /** Les jours suivent le calendrier d'Haiti, comme le reste du jeu. */
    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");

    private final JdbcTemplate jdbc;

    private record Games(long total, long group, long solo, long correct, long answered, long perfectSolo) {
    }

    private record Rankings(long ranked, Integer best, long podiums) {
    }

    @Transactional(readOnly = true)
    public PlayerStatsResponse statsOf(Long userId) {
        Games games = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(game_mode = 'GROUP'), 0) AS group_games,
                       COALESCE(SUM(game_mode = 'SOLO'), 0) AS solo_games,
                       COALESCE(SUM(correct_count), 0) AS correct,
                       COALESCE(SUM(answered_count), 0) AS answered,
                       COALESCE(SUM(game_mode = 'SOLO' AND won), 0) AS perfect_solo
                FROM game_sessions
                WHERE user_id = ? AND status = 'SUBMITTED'
                """, (rs, row) -> new Games(rs.getLong("total"), rs.getLong("group_games"),
                        rs.getLong("solo_games"), rs.getLong("correct"), rs.getLong("answered"),
                        rs.getLong("perfect_solo")), userId);

        Rankings rankings = jdbc.queryForObject("""
                SELECT COUNT(*) AS ranked, MIN(rank_position) AS best,
                       COALESCE(SUM(rank_position <= 3), 0) AS podiums
                FROM quiz_rankings
                WHERE user_id = ?
                """, (rs, row) -> new Rankings(rs.getLong("ranked"), rs.getObject("best", Integer.class),
                        rs.getLong("podiums")), userId);

        Long winnings = jdbc.queryForObject("""
                SELECT COALESCE(SUM(t.amount), 0)
                FROM wallet_transactions t
                JOIN wallets w ON w.id = t.wallet_id
                WHERE w.user_id = ? AND t.type = 'PRIZE'
                """, Long.class, userId);

        String favorite = jdbc.query("""
                SELECT c.name, COUNT(*) AS games
                FROM game_sessions gs
                JOIN quizzes q ON q.id = gs.quiz_id
                JOIN categories c ON c.id = COALESCE(gs.category_id, q.selected_category_id)
                WHERE gs.user_id = ? AND gs.status = 'SUBMITTED'
                GROUP BY c.id, c.name
                ORDER BY games DESC, c.name
                LIMIT 1
                """, (rs, row) -> rs.getString("name"), userId).stream().findFirst().orElse(null);

        // Jours joues, a l'heure d'Haiti : la conversion se fait ici et non en SQL,
        // CONVERT_TZ dependant des tables de fuseaux de MySQL, souvent absentes.
        TreeSet<LocalDate> played = new TreeSet<>();
        jdbc.query("SELECT started_at FROM game_sessions WHERE user_id = ? AND status = 'SUBMITTED'",
                rs -> {
                    Timestamp startedAt = rs.getTimestamp("started_at");
                    played.add(startedAt.toInstant().atZone(HAITI).toLocalDate());
                }, userId);
        List<LocalDate> days = List.copyOf(played.descendingSet());

        Integer accuracy = games.answered() == 0 ? null
                : (int) Math.round(100.0 * games.correct() / games.answered());

        return new PlayerStatsResponse(
                games.total(), games.group(), games.solo(), games.correct(), games.answered(), accuracy,
                rankings.ranked(), rankings.best(), rankings.podiums(), games.perfectSolo(),
                winnings == null ? 0 : winnings,
                currentStreak(days, LocalDate.now(HAITI)), bestStreak(days), favorite);
    }

    /**
     * Serie en cours : jours consecutifs joues, en partant d'aujourd'hui, ou d'hier
     * si le joueur n'a pas encore joue aujourd'hui (la serie n'est pas encore perdue).
     */
    static int currentStreak(List<LocalDate> daysDesc, LocalDate today) {
        if (daysDesc.isEmpty()) {
            return 0;
        }
        LocalDate expected = daysDesc.get(0).equals(today) ? today : today.minusDays(1);
        int streak = 0;
        for (LocalDate day : daysDesc) {
            if (!day.equals(expected)) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    /** Plus longue suite de jours consecutifs joues. */
    static int bestStreak(List<LocalDate> daysDesc) {
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : daysDesc) {
            run = previous != null && day.equals(previous.minusDays(1)) ? run + 1 : 1;
            best = Math.max(best, run);
            previous = day;
        }
        return best;
    }
}
