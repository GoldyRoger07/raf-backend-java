package com.raikiservice.raf.admin.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.admin.web.dto.PlayerSummary;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recherche de joueurs pour les administrateurs : au guichet, l'agent retrouve le
 * joueur par son telephone ou son email.
 *
 * <p>Distincte de la gestion des comptes ({@code /api/admin/users}, reservee au
 * super-administrateur) : elle ne permet de rien modifier.
 */
@Service
@RequiredArgsConstructor
public class PlayerSearchService {

    private static final String SELECT = """
            SELECT u.id, u.email, u.phone, u.first_name, u.last_name, u.status,
                   u.email_verified_at, u.phone_verified_at, u.created_at, u.is_test,
                   u.payout_terms_accepted_at,
                   (sc.user_id IS NOT NULL) AS payout_special_case, sc.note AS payout_special_case_note,
                   COALESCE(w.credit_balance, 0) AS credit_balance,
                   COALESCE(w.winnings_balance, 0) AS winnings_balance
            FROM users u
            LEFT JOIN wallets w ON w.user_id = u.id
            LEFT JOIN payout_special_cases sc ON sc.user_id = u.id
            """;

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    /**
     * Colonnes triables : nom expose a l'API -> expression SQL. Rien d'autre n'entre dans
     * l'ORDER BY. Les valeurs vides (comptes supprimes) restent en fin de liste dans les deux sens.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "(u.first_name IS NULL), LOWER(u.first_name) %1$s, LOWER(u.last_name) %1$s",
            "email", "(u.email IS NULL), LOWER(u.email) %s",
            "creditBalance", "credit_balance %s",
            "winningsBalanceHtg", "winnings_balance %s",
            "createdAt", "u.created_at %s");

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");

    @Transactional(readOnly = true)
    public Page<PlayerSummary> search(String query, Pageable pageable) {
        return search(PlayerFilter.query(query), pageable);
    }

    @Transactional(readOnly = true)
    public Page<PlayerSummary> search(PlayerFilter filter, Pageable pageable) {
        long rate = walletService.htgRate();
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String query = filter.query();
        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            // Un numero saisi sans indicatif (3712 3456) doit retrouver +50937123456.
            String digits = query.replaceAll("[^0-9]", "");
            conditions.add("(LOWER(u.email) LIKE ? OR LOWER(CONCAT(u.first_name, ' ', u.last_name)) LIKE ?"
                    + (digits.length() >= 4 ? " OR u.phone LIKE ?" : "") + ")");
            args.add(term);
            args.add(term);
            if (digits.length() >= 4) {
                args.add("%" + digits + "%");
            }
        }
        if (filter.emailVerified() != null) {
            conditions.add("u.email_verified_at IS " + (filter.emailVerified() ? "NOT NULL" : "NULL"));
        }
        if (filter.phoneVerified() != null) {
            conditions.add("u.phone_verified_at IS " + (filter.phoneVerified() ? "NOT NULL" : "NULL"));
        }
        if (filter.hasCredit() != null) {
            conditions.add("COALESCE(w.credit_balance, 0) " + (filter.hasCredit() ? "> 0" : "<= 0"));
        }
        if (filter.hasWinnings() != null) {
            conditions.add("COALESCE(w.winnings_balance, 0) " + (filter.hasWinnings() ? "> 0" : "<= 0"));
        }
        if (filter.status() != null) {
            conditions.add("u.status = ?");
            args.add(filter.status().name());
        }
        if (filter.test() != null) {
            conditions.add("u.is_test = ?");
            args.add(filter.test());
        }
        if (filter.createdFrom() != null) {
            conditions.add("u.created_at >= ?");
            args.add(Timestamp.from(filter.createdFrom().atStartOfDay(HAITI).toInstant()));
        }
        if (filter.createdTo() != null) {
            conditions.add("u.created_at < ?");
            args.add(Timestamp.from(filter.createdTo().plusDays(1).atStartOfDay(HAITI).toInstant()));
        }

        if (filter.hasToppedUp() != null) {
            conditions.add((filter.hasToppedUp() ? "" : "NOT ") + "EXISTS (SELECT 1 FROM topup_requests t"
                    + " WHERE t.user_id = u.id AND t.status = 'CONFIRMED')");
        }
        if (filter.activity() != null) {
            String played = "EXISTS (SELECT 1 FROM game_sessions g WHERE g.user_id = u.id";
            String playedSince = played + " AND g.started_at >= ?)";
            Timestamp days7 = Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS));
            Timestamp days30 = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
            switch (filter.activity()) {
                case LAST_7_DAYS -> {
                    conditions.add(playedSince);
                    args.add(days7);
                }
                case LAST_30_DAYS -> {
                    conditions.add(playedSince);
                    args.add(days30);
                }
                case INACTIVE_30_DAYS -> {
                    conditions.add(played + ") AND NOT " + playedSince);
                    args.add(days30);
                }
                case NEVER -> conditions.add("NOT " + played + ")");
            }
        }

        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users u LEFT JOIN wallets w ON w.user_id = u.id" + where,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<PlayerSummary> content = jdbc.query(
                SELECT + where + orderBy(pageable.getSort()) + " LIMIT ? OFFSET ?",
                (rs, row) -> map(rs, rate), pageArgs.toArray());

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /** Tri demande, puis l'id pour une pagination stable ; par defaut les derniers inscrits d'abord. */
    private static String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String expression = SORTABLE.get(order.getProperty());
            if (expression == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Tri impossible sur \"" + order.getProperty() + "\".");
            }
            parts.add(String.format(expression, order.isAscending() ? "ASC" : "DESC"));
        }
        if (parts.isEmpty()) {
            return " ORDER BY u.created_at DESC, u.id DESC";
        }
        Sort.Order first = sort.iterator().next();
        return " ORDER BY " + String.join(", ", parts) + ", u.id " + (first.isAscending() ? "ASC" : "DESC");
    }

    @Transactional(readOnly = true)
    public PlayerSummary get(Long userId) {
        long rate = walletService.htgRate();
        return jdbc.query(SELECT + " WHERE u.id = ?", (rs, row) -> map(rs, rate), userId).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Joueur"));
    }

    private static PlayerSummary map(ResultSet rs, long rate) throws SQLException {
        long winnings = rs.getLong("winnings_balance");
        return new PlayerSummary(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("status"),
                rs.getTimestamp("email_verified_at") != null,
                rs.getTimestamp("phone_verified_at") != null,
                rs.getBoolean("is_test"),
                rs.getLong("credit_balance"),
                winnings,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("payout_terms_accepted_at") == null ? null
                        : rs.getTimestamp("payout_terms_accepted_at").toInstant(),
                rs.getBoolean("payout_special_case"),
                rs.getString("payout_special_case_note"));
    }
}
