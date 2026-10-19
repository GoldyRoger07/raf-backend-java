package com.raikiservice.raf.admin.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.admin.web.dto.DashboardResponse;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chiffres du tableau de bord, en requetes d'agregation directes : une page
 * d'accueil d'administration ne doit pas charger des milliers d'entites pour
 * afficher des totaux.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        Instant now = Instant.now();
        Timestamp since30 = Timestamp.from(now.minus(30, ChronoUnit.DAYS));
        Timestamp since7 = Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        long rate = walletService.htgRate();

        DashboardResponse.Players players = new DashboardResponse.Players(
                count("SELECT COUNT(*) FROM users"),
                count("SELECT COUNT(*) FROM users WHERE created_at >= ?", since7),
                count("SELECT COUNT(*) FROM users WHERE phone_verified_at IS NOT NULL"));

        Map<String, Long> quizzes = new LinkedHashMap<>();
        for (QuizStatus status : QuizStatus.values()) {
            quizzes.put(status.name(), 0L);
        }
        jdbc.query("SELECT status, COUNT(*) AS n FROM quizzes GROUP BY status",
                rs -> {
                    quizzes.put(rs.getString("status"), rs.getLong("n"));
                });

        long credits = count("SELECT COALESCE(SUM(credit_balance), 0) FROM wallets");
        long winnings = count("SELECT COALESCE(SUM(winnings_balance), 0) FROM wallets");

        long ticketSales = -count("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions "
                + "WHERE type = 'TICKET_PURCHASE' AND created_at >= ?", since30);
        long prizes = count("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions "
                + "WHERE type = 'PRIZE' AND created_at >= ?", since30);
        long topUps = count("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions "
                + "WHERE type = 'TOPUP' AND created_at >= ?", since30);

        DashboardResponse.Activity activity = new DashboardResponse.Activity(
                ticketSales, ticketSales * rate,
                count("SELECT COUNT(*) FROM tickets WHERE purchased_at >= ? AND status <> 'REFUNDED'",
                        since30),
                prizes, prizes * rate,
                topUps, topUps * rate,
                count("SELECT COALESCE(SUM(net_htg), 0) FROM withdrawal_requests "
                        + "WHERE status = 'PAID' AND paid_at >= ?", since30));

        DashboardResponse.Pending pending = new DashboardResponse.Pending(
                count("SELECT COUNT(*) FROM withdrawal_requests WHERE status = 'PENDING'"),
                count("SELECT COALESCE(SUM(net_htg), 0) FROM withdrawal_requests WHERE status = 'PENDING'"),
                count("SELECT COUNT(*) FROM withdrawal_requests WHERE status = 'APPROVED'"),
                // Seuls comptent les signalements encore actionnables : une fois le
                // quiz termine, les gains sont verses.
                count("SELECT COUNT(*) FROM game_sessions s JOIN quizzes q ON q.id = s.quiz_id "
                        + "WHERE s.flagged = TRUE AND s.status = 'SUBMITTED' "
                        + "AND q.status NOT IN ('FINISHED', 'CANCELLED')"),
                count("SELECT COUNT(*) FROM topup_requests WHERE status = 'PENDING'"));

        List<DashboardResponse.LastReconciliation> last = jdbc.query(
                "SELECT id, status, started_at, finding_count FROM reconciliation_runs "
                        + "ORDER BY started_at DESC LIMIT 1",
                (rs, row) -> new DashboardResponse.LastReconciliation(rs.getLong("id"),
                        rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                        rs.getInt("finding_count")));

        return new DashboardResponse(now, rate, players, quizzes,
                new DashboardResponse.Money(credits, credits * rate, winnings, winnings * rate),
                activity, pending, last.isEmpty() ? null : last.get(0));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
