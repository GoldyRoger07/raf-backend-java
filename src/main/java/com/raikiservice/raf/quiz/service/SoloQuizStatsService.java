package com.raikiservice.raf.quiz.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activite des quiz solo pour le back-office : parties jouees, joueurs, sans-fautes,
 * recettes et prix verses, avec filtres et tri.
 *
 * <p>Les parties des comptes de test sont exclues des chiffres (comme au tableau de
 * bord). Les recettes sont les credits d'entree payes, convertis au taux actuel du
 * credit ; les prix sont les gourdes reellement versees pour les sans-fautes.
 */
@Service
@RequiredArgsConstructor
public class SoloQuizStatsService {

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    public enum Period {
        LAST_7_DAYS, LAST_30_DAYS, ALL_TIME
    }

    /** Filtre sur les sans-fautes de la periode. */
    public enum PerfectFilter {
        ALL,
        /** Au moins un sans-faute. */
        WITH,
        /** Joue, mais aucun sans-faute. */
        WITHOUT,
        /** Aucune partie sur la periode. */
        NEVER_PLAYED
    }

    /** Filtre sur le resultat de la periode (recettes moins prix verses). */
    public enum ResultFilter {
        ALL, PROFIT, LOSS
    }

    public enum Sort {
        RECENT, MOST_PLAYED, MOST_PERFECT, BEST_RESULT, WORST_RESULT, LAST_PLAYED
    }

    public record Filters(QuizStatus status, Period period, String query, Long categoryId,
            PerfectFilter perfect, ResultFilter result, Sort sort) {
    }

    public record Row(
            Long id,
            String title,
            boolean test,
            QuizStatus status,
            Long categoryId,
            String categoryName,
            long entryCredits,
            long entryHtg,
            long prizeHtg,
            int questionsPerGame,
            int questionDurationSeconds,
            long games,
            long players,
            long perfectGames,
            /** Pourcentage de parties gagnees, arrondi a une decimale. */
            double perfectRate,
            /** Bonnes reponses en moyenne par partie terminee, null sans partie terminee. */
            Double averageCorrect,
            long revenueHtg,
            long prizesPaidHtg,
            long resultHtg,
            Instant lastPlayedAt,
            /** Masque aux joueurs (voir Quiz#hidden). */
            boolean hidden) {
    }

    public record Summary(long quizzes, long games, long perfectGames,
            long revenueHtg, long prizesPaidHtg, long resultHtg) {
    }

    public record StatsPage(Summary summary, Page<Row> page) {
    }

    @Transactional(readOnly = true)
    public StatsPage stats(Filters filters, Pageable pageable) {
        // Entier lu des parametres : sans risque une fois insere dans la requete, et il
        // evite de repeter le meme parametre a chaque calcul en gourdes.
        long rate = walletService.htgRate();
        String revenue = "(COALESCE(s.entry_credits, 0) * " + rate + ")";
        String result = "(" + revenue + " - COALESCE(s.prizes, 0))";

        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(start(filters.period())));
        StringBuilder where = new StringBuilder(" WHERE q.game_mode = 'SOLO' AND q.status = ?");
        args.add(filters.status().name());
        if (filters.query() != null && !filters.query().isBlank()) {
            where.append(" AND LOWER(q.title) LIKE ?");
            args.add("%" + filters.query().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%");
        }
        if (filters.categoryId() != null) {
            where.append(" AND q.selected_category_id = ?");
            args.add(filters.categoryId());
        }
        switch (filters.perfect() == null ? PerfectFilter.ALL : filters.perfect()) {
            case WITH -> where.append(" AND COALESCE(s.perfect, 0) > 0");
            case WITHOUT -> where.append(" AND COALESCE(s.games, 0) > 0 AND COALESCE(s.perfect, 0) = 0");
            case NEVER_PLAYED -> where.append(" AND COALESCE(s.games, 0) = 0");
            case ALL -> { }
        }
        switch (filters.result() == null ? ResultFilter.ALL : filters.result()) {
            case PROFIT -> where.append(" AND ").append(result).append(" > 0");
            case LOSS -> where.append(" AND ").append(result).append(" < 0");
            case ALL -> { }
        }
        String from = FROM + where;

        Summary summary = jdbc.queryForObject(
                "SELECT COUNT(*), COALESCE(SUM(s.games), 0), COALESCE(SUM(s.perfect), 0), "
                        + "COALESCE(SUM(" + revenue + "), 0), COALESCE(SUM(s.prizes), 0)" + from,
                (rs, row) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(4) - rs.getLong(5)),
                args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<Row> rows = jdbc.query("SELECT q.id, q.title, q.is_test, q.status, q.selected_category_id, c.name, "
                + "q.ticket_price_credits, COALESCE(q.solo_prize_htg, 0), q.questions_per_game, "
                + "q.question_duration_seconds, COALESCE(s.games, 0), COALESCE(s.players, 0), "
                + "COALESCE(s.perfect, 0), s.avg_correct, " + revenue + ", COALESCE(s.prizes, 0), s.last_played, q.hidden"
                + from + " ORDER BY " + order(filters.sort(), result) + " LIMIT ? OFFSET ?",
                (rs, row) -> {
                    long categoryId = rs.getLong(5);
                    Long category = rs.wasNull() ? null : categoryId;
                    long entryCredits = rs.getLong(7);
                    long games = rs.getLong(11);
                    long perfect = rs.getLong(13);
                    double avg = rs.getDouble(14);
                    Double average = rs.wasNull() ? null : Math.round(avg * 10) / 10.0;
                    long revenueHtg = rs.getLong(15);
                    long prizes = rs.getLong(16);
                    Timestamp last = rs.getTimestamp(17);
                    return new Row(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                            QuizStatus.valueOf(rs.getString(4)), category, rs.getString(6),
                            entryCredits, entryCredits * rate, rs.getLong(8), rs.getInt(9), rs.getInt(10),
                            games, rs.getLong(12), perfect,
                            games == 0 ? 0 : Math.round(perfect * 1000.0 / games) / 10.0, average,
                            revenueHtg, prizes, revenueHtg - prizes, last == null ? null : last.toInstant(),
                            rs.getBoolean(18));
                }, pageArgs.toArray());

        return new StatsPage(summary, new PageImpl<>(rows, pageable, summary.quizzes()));
    }

    /** Quiz solo et leur activite agregee depuis le debut de la periode (premier parametre). */
    private static final String FROM = """
             FROM quizzes q
             LEFT JOIN categories c ON c.id = q.selected_category_id
             LEFT JOIN (SELECT gs.quiz_id,
                               COUNT(*) AS games,
                               COUNT(DISTINCT gs.user_id) AS players,
                               SUM(CASE WHEN gs.won THEN 1 ELSE 0 END) AS perfect,
                               SUM(COALESCE(gs.entry_price_credits, 0)) AS entry_credits,
                               SUM(CASE WHEN gs.won THEN COALESCE(gs.prize_htg, 0) ELSE 0 END) AS prizes,
                               AVG(CASE WHEN gs.status = 'SUBMITTED' THEN gs.correct_count END) AS avg_correct,
                               MAX(gs.started_at) AS last_played
                        FROM game_sessions gs
                        JOIN users u ON u.id = gs.user_id
                        WHERE gs.game_mode = 'SOLO' AND u.is_test = FALSE AND gs.started_at >= ?
                        GROUP BY gs.quiz_id) s ON s.quiz_id = q.id
            """;

    private static String order(Sort sort, String result) {
        return switch (sort == null ? Sort.RECENT : sort) {
            case RECENT -> "q.created_at DESC, q.id DESC";
            case MOST_PLAYED -> "COALESCE(s.games, 0) DESC, q.id DESC";
            case MOST_PERFECT -> "COALESCE(s.perfect, 0) DESC, COALESCE(s.games, 0) DESC, q.id DESC";
            case BEST_RESULT -> result + " DESC, q.id DESC";
            case WORST_RESULT -> result + " ASC, q.id DESC";
            case LAST_PLAYED -> "s.last_played IS NULL, s.last_played DESC, q.id DESC";
        };
    }

    private static Instant start(Period period) {
        Instant now = Instant.now();
        return switch (period == null ? Period.ALL_TIME : period) {
            case LAST_7_DAYS -> now.minus(7, ChronoUnit.DAYS);
            case LAST_30_DAYS -> now.minus(30, ChronoUnit.DAYS);
            case ALL_TIME -> Instant.EPOCH;
        };
    }
}
