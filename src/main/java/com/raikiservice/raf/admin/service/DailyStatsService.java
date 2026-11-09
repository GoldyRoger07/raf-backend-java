package com.raikiservice.raf.admin.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.raikiservice.raf.admin.web.dto.DailyStatsResponse;
import com.raikiservice.raf.admin.web.dto.DailyStatsResponse.Day;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activite jour par jour, aux jours d'Haiti, pour comparer aujourd'hui a hier et
 * tracer les courbes du tableau de bord.
 *
 * <p>Les dates sont stockees en UTC. Plutot que CONVERT_TZ (qui depend des tables
 * de fuseaux de MySQL, souvent absentes), on agrege par heure UTC en SQL puis on
 * range chaque heure dans son jour d'Haiti ici : le decalage d'Haiti etant d'un
 * nombre entier d'heures, aucune heure n'est a cheval sur deux jours.
 */
@Service
@RequiredArgsConstructor
public class DailyStatsService {

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final int MIN_DAYS = 2;
    public static final int MAX_DAYS = 90;

    /** Chaque chiffre : son expression, sa source (hors tests) et sa colonne de date. */
    private enum Metric {
        SALES_CREDITS("-COALESCE(SUM(t.amount), 0)",
                RealActivity.TRANSACTIONS + "AND t.type IN ('TICKET_PURCHASE', 'SOLO_ENTRY')", "t.created_at"),
        REFUNDS_CREDITS("COALESCE(SUM(t.amount), 0)",
                RealActivity.TRANSACTIONS + "AND t.type = 'REFUND' AND t.balance_type = 'CREDIT'", "t.created_at"),
        PRIZES_HTG("COALESCE(SUM(t.amount), 0)",
                RealActivity.TRANSACTIONS + "AND t.type = 'PRIZE'", "t.created_at"),
        COMMISSIONS_HTG("COALESCE(SUM(t.amount), 0)",
                RealActivity.TRANSACTIONS + "AND t.type = 'COMMISSION'", "t.created_at"),
        INVITE_BONUSES_CREDITS("COALESCE(SUM(t.amount), 0)",
                RealActivity.TRANSACTIONS + "AND t.type = 'INVITE_BONUS'", "t.created_at"),
        TOP_UPS_CREDITS("COALESCE(SUM(t.amount), 0)",
                RealActivity.TRANSACTIONS + "AND t.type = 'TOPUP'", "t.created_at"),
        WITHDRAWAL_FEES_HTG("COALESCE(SUM(wr.fee_htg), 0)",
                RealActivity.WITHDRAWALS + "AND wr.status = 'PAID'", "wr.paid_at"),
        WITHDRAWALS_PAID_HTG("COALESCE(SUM(wr.net_htg), 0)",
                RealActivity.WITHDRAWALS + "AND wr.status = 'PAID'", "wr.paid_at"),
        TICKETS("COUNT(*)",
                RealActivity.TICKETS + "AND tk.status <> 'REFUNDED'", "tk.purchased_at"),
        SOLO_GAMES("COUNT(*)",
                RealActivity.SESSIONS + "AND gs.game_mode = 'SOLO'", "gs.started_at"),
        // Comptes supprimes et comptes de test ne sont pas des joueurs.
        NEW_PLAYERS("COUNT(*)",
                "FROM users WHERE is_test = FALSE AND status <> 'DELETED' ", "created_at");

        final String expression;
        final String source;
        final String column;

        Metric(String expression, String source, String column) {
            this.expression = expression;
            this.source = source;
            this.column = column;
        }
    }

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    /** Les {@code days} derniers jours d'Haiti, aujourd'hui compris. */
    @Transactional(readOnly = true)
    public DailyStatsResponse daily(int days) {
        int count = Math.clamp(days, MIN_DAYS, MAX_DAYS);
        ZonedDateTime now = ZonedDateTime.now(HAITI);
        LocalDate today = now.toLocalDate();
        LocalDate first = today.minusDays(count - 1L);
        Timestamp lo = Timestamp.from(first.atStartOfDay(HAITI).toInstant());
        Timestamp hi = Timestamp.from(now.toInstant());
        long rate = walletService.htgRate();

        Map<LocalDate, Map<Metric, Long>> byDay = new HashMap<>();
        for (Metric metric : Metric.values()) {
            jdbc.query("SELECT DATE_FORMAT(" + metric.column + ", '%Y-%m-%d %H:00') AS h, "
                    + metric.expression + " AS v " + metric.source
                    + "AND " + metric.column + " >= ? AND " + metric.column + " < ? GROUP BY h",
                    rs -> {
                        byDay.computeIfAbsent(haitiDay(rs.getString("h")), d -> new EnumMap<>(Metric.class))
                                .merge(metric, rs.getLong("v"), Long::sum);
                    }, lo, hi);
        }

        // Joueurs distincts : ne s'additionnent pas d'une heure a l'autre.
        Map<LocalDate, Set<Long>> players = new HashMap<>();
        jdbc.query("SELECT DISTINCT gs.user_id, DATE_FORMAT(gs.started_at, '%Y-%m-%d %H:00') AS h "
                + RealActivity.SESSIONS + "AND gs.started_at >= ? AND gs.started_at < ?",
                rs -> {
                    players.computeIfAbsent(haitiDay(rs.getString("h")), d -> new HashSet<>())
                            .add(rs.getLong("user_id"));
                }, lo, hi);

        List<Day> series = new ArrayList<>(count);
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            series.add(day(date, byDay.getOrDefault(date, Map.of()),
                    players.getOrDefault(date, Set.of()).size(), rate));
        }

        // Hier, arrete a la meme heure qu'aujourd'hui (meme heure murale, changement d'heure compris).
        LocalDate yesterday = today.minusDays(1);
        Instant yesterdayStart = yesterday.atStartOfDay(HAITI).toInstant();
        Instant yesterdayNow = now.minusDays(1).toInstant();
        Day sameTime = totals(yesterday, yesterdayStart, yesterdayNow, rate);

        return new DailyStatsResponse(now.toInstant(), rate,
                series.get(series.size() - 1), series.get(series.size() - 2), sameTime, series);
    }

    /** Chiffres exacts d'une fenetre de temps. */
    private Day totals(LocalDate date, Instant from, Instant to, long rate) {
        Timestamp lo = Timestamp.from(from);
        Timestamp hi = Timestamp.from(to);
        Map<Metric, Long> values = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            values.put(metric, value("SELECT " + metric.expression + " " + metric.source
                    + "AND " + metric.column + " >= ? AND " + metric.column + " < ?", lo, hi));
        }
        long active = value("SELECT COUNT(DISTINCT gs.user_id) " + RealActivity.SESSIONS
                + "AND gs.started_at >= ? AND gs.started_at < ?", lo, hi);
        return day(date, values, active, rate);
    }

    private static Day day(LocalDate date, Map<Metric, Long> values, long activePlayers, long rate) {
        long salesHtg = get(values, Metric.SALES_CREDITS) * rate;
        long refundsHtg = get(values, Metric.REFUNDS_CREDITS) * rate;
        long prizesHtg = get(values, Metric.PRIZES_HTG);
        long inviteBonusesHtg = get(values, Metric.INVITE_BONUSES_CREDITS) * rate;
        long resultHtg = salesHtg - refundsHtg - prizesHtg - get(values, Metric.COMMISSIONS_HTG)
                - inviteBonusesHtg + get(values, Metric.WITHDRAWAL_FEES_HTG);
        return new Day(date,
                get(values, Metric.NEW_PLAYERS),
                activePlayers,
                get(values, Metric.TICKETS),
                get(values, Metric.SOLO_GAMES),
                salesHtg,
                prizesHtg,
                resultHtg,
                get(values, Metric.TOP_UPS_CREDITS) * rate,
                get(values, Metric.WITHDRAWALS_PAID_HTG));
    }

    private static long get(Map<Metric, Long> values, Metric metric) {
        return values.getOrDefault(metric, 0L);
    }

    /** « 2026-11-09 03:00 » (heure UTC) -> jour d'Haiti. */
    private static LocalDate haitiDay(String utcHour) {
        return LocalDateTime.parse(utcHour, HOUR).toInstant(ZoneOffset.UTC).atZone(HAITI).toLocalDate();
    }

    private long value(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
