package com.raikiservice.raf.wallet.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cas de retrait particuliers (table {@code payout_special_cases}), geres depuis l'ecran
 * Retraits du back-office.
 *
 * <p>Un joueur de la liste voit, avant sa prochaine partie solo, l'accord « RAF peut me
 * payer en plusieurs fois » (PayoutTermsService) et ses retraits peuvent etre verses par
 * tranches (WithdrawalService#payInstallment). Les autres joueurs ne voient rien et sont
 * payes en une fois. Retirer un joueur garde son accord ; un retrait deja verse en partie
 * se termine normalement.
 */
@Service
@RequiredArgsConstructor
public class PayoutSpecialCaseService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /** Ligne de la liste du back-office. */
    public record SpecialCase(Long playerId, String playerName, String email, String phone, String note,
            Instant addedAt, Long addedBy, Instant termsAcceptedAt, boolean partiallyPaidWithdrawal) {
    }

    @Transactional(readOnly = true)
    public boolean contains(Long userId) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM payout_special_cases WHERE user_id = ?)", Boolean.class, userId);
        return Boolean.TRUE.equals(found);
    }

    /** Joueurs de la liste parmi {@code userIds}, pour la file des retraits. */
    @Transactional(readOnly = true)
    public Set<Long> among(Collection<Long> userIds) {
        Set<Long> found = new HashSet<>();
        if (userIds.isEmpty()) {
            return found;
        }
        named.query("SELECT user_id FROM payout_special_cases WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", userIds), rs -> {
                    found.add(rs.getLong(1));
                });
        return found;
    }

    /** La liste, le plus recemment ajoute d'abord. */
    @Transactional(readOnly = true)
    public List<SpecialCase> list() {
        return jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, u.email, u.phone, c.note, c.created_at, c.added_by,
                       u.payout_terms_accepted_at,
                       EXISTS (SELECT 1 FROM withdrawal_requests w
                               WHERE w.user_id = u.id AND w.status = 'PARTIALLY_PAID') AS partial
                FROM payout_special_cases c
                JOIN users u ON u.id = c.user_id
                ORDER BY c.created_at DESC, u.id
                """,
                (rs, i) -> {
                    Timestamp accepted = rs.getTimestamp("payout_terms_accepted_at");
                    Long addedBy = rs.getObject("added_by", Long.class);
                    return new SpecialCase(
                            rs.getLong("id"),
                            rs.getString("first_name") + " " + rs.getString("last_name"),
                            rs.getString("email"),
                            rs.getString("phone"),
                            rs.getString("note"),
                            rs.getTimestamp("created_at").toInstant(),
                            addedBy,
                            accepted == null ? null : accepted.toInstant(),
                            rs.getBoolean("partial"));
                });
    }

    /** Ajoute le joueur ; deja present, sa note est mise a jour. Trace au journal d'audit. */
    @Transactional
    public void add(Long adminId, Long userId, String note) {
        userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Joueur"));
        String cleaned = note == null || note.isBlank() ? null : note.trim();
        boolean present = contains(userId);
        if (present) {
            jdbc.update("UPDATE payout_special_cases SET note = ? WHERE user_id = ?", cleaned, userId);
        } else {
            jdbc.update("INSERT INTO payout_special_cases (user_id, note, added_by, created_at) VALUES (?, ?, ?, ?)",
                    userId, cleaned, adminId, Timestamp.from(Instant.now()));
        }
        auditService.record(adminId, present ? "PAYOUT_SPECIAL_CASE_UPDATED" : "PAYOUT_SPECIAL_CASE_ADDED",
                "User", String.valueOf(userId), null, cleaned);
    }

    /** Retire le joueur : plus d'accord a donner, ses prochains retraits sont payes en une fois. */
    @Transactional
    public void remove(Long adminId, Long userId) {
        if (jdbc.update("DELETE FROM payout_special_cases WHERE user_id = ?", userId) > 0) {
            auditService.record(adminId, "PAYOUT_SPECIAL_CASE_REMOVED", "User", String.valueOf(userId));
        }
    }
}
