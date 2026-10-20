package com.raikiservice.raf.admin.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.admin.web.dto.PlayerSummary;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
                   u.email_verified_at, u.phone_verified_at, u.created_at,
                   COALESCE(w.credit_balance, 0) AS credit_balance,
                   COALESCE(w.winnings_balance, 0) AS winnings_balance
            FROM users u
            LEFT JOIN wallets w ON w.user_id = u.id
            """;

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    @Transactional(readOnly = true)
    public Page<PlayerSummary> search(String query, Pageable pageable) {
        long rate = walletService.htgRate();
        String where = "";
        List<Object> args = new ArrayList<>();

        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            // Un numero saisi sans indicatif (3712 3456) doit retrouver +50937123456.
            String digits = query.replaceAll("[^0-9]", "");
            where = " WHERE LOWER(u.email) LIKE ? OR LOWER(CONCAT(u.first_name, ' ', u.last_name)) LIKE ?"
                    + (digits.length() >= 4 ? " OR u.phone LIKE ?" : "");
            args.add(term);
            args.add(term);
            if (digits.length() >= 4) {
                args.add("%" + digits + "%");
            }
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u" + where, Long.class,
                args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<PlayerSummary> content = jdbc.query(
                SELECT + where + " ORDER BY u.created_at DESC, u.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs, rate), pageArgs.toArray());

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
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
                rs.getLong("credit_balance"),
                winnings,
                rs.getTimestamp("created_at").toInstant());
    }
}
