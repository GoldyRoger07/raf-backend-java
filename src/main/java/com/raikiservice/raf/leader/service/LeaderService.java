package com.raikiservice.raf.leader.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.auth.domain.Role;
import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.RoleRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.leader.web.dto.LeaderDtos;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderCommission;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderPlayer;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderSummary;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion des leaders par le super-administrateur : nommer ou retirer un leader,
 * regler sa commission, lui rattacher ou retirer des joueurs, consulter ce qu'il a
 * touche. Le versement des commissions est dans {@link CommissionService}.
 *
 * <p>Un joueur n'a qu'un leader. Un leader ne peut pas etre le joueur d'un autre
 * leader, ni un compte du personnel, de test ou supprime.
 */
@Service
@RequiredArgsConstructor
public class LeaderService {

    private static final String IS_LEADER = "EXISTS (SELECT 1 FROM user_roles ur JOIN roles r "
            + "ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.name = 'LEADER')";

    private static final String SUMMARY = """
            SELECT u.id, u.first_name, u.last_name, u.email, u.phone, u.status,
                   COALESCE(pr.commission_percent, 0) AS commission_percent,
                   COALESCE(pr.signup_bonus_credits, 0) AS signup_bonus_credits,
                   (SELECT COUNT(*) FROM leader_players lp WHERE lp.leader_id = u.id) AS player_count,
                   (SELECT COALESCE(SUM(c.amount_htg), 0) FROM leader_commissions c
                     WHERE c.leader_id = u.id AND c.created_at >= ?) AS last_30_days,
                   (SELECT COALESCE(SUM(c.amount_htg), 0) FROM leader_commissions c
                     WHERE c.leader_id = u.id) AS total,
                   (SELECT COALESCE(SUM(t.credits), 0) FROM leader_credit_transfers t
                     WHERE t.leader_id = u.id AND t.created_at >= ?) AS transfers_last_30_days,
                   (SELECT COALESCE(SUM(t.credits), 0) FROM leader_credit_transfers t
                     WHERE t.leader_id = u.id) AS transfers_total
            FROM users u
            LEFT JOIN leader_profiles pr ON pr.user_id = u.id
            WHERE %s
            """.formatted(IS_LEADER);

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;

    // --- Lecture ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<LeaderSummary> list(String query, Pageable pageable) {
        String where = "";
        List<Object> args = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            String digits = query.replaceAll("[^0-9]", "");
            where = " AND (LOWER(u.email) LIKE ? OR LOWER(CONCAT(u.first_name, ' ', u.last_name)) LIKE ?"
                    + (digits.length() >= 4 ? " OR u.phone LIKE ?" : "") + ")";
            args.add(term);
            args.add(term);
            if (digits.length() >= 4) {
                args.add("%" + digits + "%");
            }
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u WHERE " + IS_LEADER + where,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>();
        pageArgs.add(since30Days());
        pageArgs.add(since30Days());
        pageArgs.addAll(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<LeaderSummary> content = jdbc.query(
                SUMMARY + where + " ORDER BY u.first_name, u.last_name, u.id LIMIT ? OFFSET ?",
                (rs, row) -> summary(rs), pageArgs.toArray());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public LeaderSummary get(Long leaderId) {
        return jdbc.query(SUMMARY + " AND u.id = ?", (rs, row) -> summary(rs), since30Days(), since30Days(), leaderId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Leader"));
    }

    @Transactional(readOnly = true)
    public Page<LeaderPlayer> players(Long leaderId, Pageable pageable) {
        get(leaderId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM leader_players WHERE leader_id = ?",
                Long.class, leaderId);
        List<LeaderPlayer> content = jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, u.email, u.phone, u.status, u.is_test,
                       lp.added_at, lp.via_invite, lp.signup_bonus_credits,
                       b.created_at AS bonus_paid_at,
                       (SELECT COALESCE(SUM(c.amount_htg), 0) FROM leader_commissions c
                         WHERE c.leader_id = lp.leader_id AND c.player_id = u.id) AS commissions,
                       (SELECT MAX(g.started_at) FROM game_sessions g WHERE g.user_id = u.id) AS last_played
                FROM leader_players lp
                JOIN users u ON u.id = lp.player_id
                LEFT JOIN leader_invite_bonuses b ON b.player_id = lp.player_id
                WHERE lp.leader_id = ?
                ORDER BY lp.added_at DESC, u.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new LeaderPlayer(
                        rs.getLong("id"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("status"),
                        rs.getBoolean("is_test"),
                        rs.getTimestamp("added_at").toInstant(),
                        rs.getBoolean("via_invite"),
                        rs.getInt("signup_bonus_credits"),
                        instant(rs, "bonus_paid_at"),
                        rs.getLong("commissions"),
                        instant(rs, "last_played")),
                leaderId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public Page<LeaderCommission> commissions(Long leaderId, Pageable pageable) {
        get(leaderId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM leader_commissions WHERE leader_id = ?",
                Long.class, leaderId);
        List<LeaderCommission> content = jdbc.query("""
                SELECT c.id, c.player_id, u.first_name, u.last_name, c.source, c.quiz_id, q.title,
                       c.session_id, c.base_credits, c.htg_rate, c.commission_percent, c.amount_htg,
                       c.created_at
                FROM leader_commissions c
                JOIN users u ON u.id = c.player_id
                JOIN quizzes q ON q.id = c.quiz_id
                WHERE c.leader_id = ?
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new LeaderCommission(
                        rs.getLong("id"),
                        rs.getLong("player_id"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("source"),
                        rs.getLong("quiz_id"),
                        rs.getString("title"),
                        (Long) rs.getObject("session_id", Long.class),
                        rs.getLong("base_credits"),
                        rs.getLong("htg_rate"),
                        rs.getBigDecimal("commission_percent"),
                        rs.getLong("amount_htg"),
                        rs.getTimestamp("created_at").toInstant()),
                leaderId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    // --- Ecriture -----------------------------------------------------------------

    /** Donne le role LEADER a un compte joueur et fixe sa commission. */
    @Transactional
    public LeaderSummary promote(Long actorId, Long userId, BigDecimal percent) {
        BigDecimal commission = validPercent(percent);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Joueur"));

        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.LEADER_NOT_ELIGIBLE, "Ce compte a ete supprime.");
        }
        if (user.isTest()) {
            throw new ApiException(ErrorCode.LEADER_NOT_ELIGIBLE,
                    "Un compte de test ne peut pas devenir leader : il ne toucherait jamais de commission.");
        }
        if (hasRole(user, RoleName.LEADER)) {
            throw new ApiException(ErrorCode.LEADER_NOT_ELIGIBLE, "Ce joueur est deja leader.");
        }
        if (user.getRoles().stream().anyMatch(role -> role.getName().isStaff())) {
            throw new ApiException(ErrorCode.LEADER_NOT_ELIGIBLE,
                    "Un compte du personnel ne peut pas etre leader.");
        }
        String currentLeader = leaderNameOf(userId);
        if (currentLeader != null) {
            throw new ApiException(ErrorCode.LEADER_NOT_ELIGIBLE,
                    "Ce joueur est rattache au leader " + currentLeader + " : retirez-le d'abord de ses joueurs.");
        }

        Role leader = roleRepository.findByName(RoleName.LEADER)
                .orElseThrow(() -> ApiException.notFound("Role LEADER"));
        user.getRoles().add(leader);
        // Ecrit tout de suite : les lectures qui suivent passent par JDBC.
        userRepository.saveAndFlush(user);

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO leader_profiles (user_id, commission_percent, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE commission_percent = VALUES(commission_percent),
                                        updated_at = VALUES(updated_at)
                """, userId, commission, now, now);

        auditService.record(actorId, "LEADER_PROMOTED", "User", String.valueOf(userId),
                null, "commission " + commission.toPlainString() + " %");
        return get(userId);
    }

    /**
     * Retire le role LEADER. Ses joueurs sont detaches (ils pourront rejoindre un autre
     * leader) ; l'historique des commissions est garde, son reglage aussi.
     */
    @Transactional
    public void demote(Long actorId, Long leaderId) {
        get(leaderId);
        User user = userRepository.findById(leaderId)
                .orElseThrow(() -> ApiException.notFound("Leader"));
        user.getRoles().removeIf(role -> role.getName() == RoleName.LEADER);
        userRepository.saveAndFlush(user);

        int detached = jdbc.update("DELETE FROM leader_players WHERE leader_id = ?", leaderId);
        auditService.record(actorId, "LEADER_DEMOTED", "User", String.valueOf(leaderId),
                null, detached + " joueur(s) detache(s)");
    }

    /**
     * Le role LEADER a ete retire ailleurs (ecran des comptes) : ses joueurs sont
     * detaches, comme par {@link #demote}.
     */
    @Transactional
    public void detachPlayers(Long actorId, Long leaderId) {
        int detached = jdbc.update("DELETE FROM leader_players WHERE leader_id = ?", leaderId);
        if (detached > 0) {
            auditService.record(actorId, "LEADER_DEMOTED", "User", String.valueOf(leaderId),
                    null, detached + " joueur(s) detache(s)");
        }
    }

    /** Nouveau pourcentage : il vaut pour les commissions a venir, pas pour celles deja versees. */
    @Transactional
    public LeaderSummary setCommission(Long actorId, Long leaderId, BigDecimal percent) {
        BigDecimal commission = validPercent(percent);
        LeaderSummary before = get(leaderId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO leader_profiles (user_id, commission_percent, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE commission_percent = VALUES(commission_percent),
                                        updated_at = VALUES(updated_at)
                """, leaderId, commission, now, now);
        auditService.record(actorId, "LEADER_COMMISSION_CHANGED", "User", String.valueOf(leaderId),
                before.commissionPercent().toPlainString() + " %", commission.toPlainString() + " %");
        return get(leaderId);
    }

    /**
     * Bonus offert a chaque joueur qui s'inscrit avec le lien de ce leader. Vaut pour
     * les inscriptions a venir : le bonus deja promis a un inscrit ne change pas.
     */
    @Transactional
    public LeaderSummary setSignupBonus(Long actorId, Long leaderId, Integer credits) {
        if (credits == null || credits < 0 || credits > LeaderDtos.MAX_SIGNUP_BONUS_CREDITS) {
            throw new ApiException(ErrorCode.LEADER_BONUS_INVALID);
        }
        LeaderSummary before = get(leaderId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO leader_profiles (user_id, commission_percent, signup_bonus_credits, created_at, updated_at)
                VALUES (?, 0, ?, ?, ?)
                ON DUPLICATE KEY UPDATE signup_bonus_credits = VALUES(signup_bonus_credits),
                                        updated_at = VALUES(updated_at)
                """, leaderId, credits, now, now);
        auditService.record(actorId, "LEADER_SIGNUP_BONUS_CHANGED", "User", String.valueOf(leaderId),
                before.signupBonusCredits() + " credits", credits + " credits");
        return get(leaderId);
    }

    @Transactional
    public void addPlayer(Long actorId, Long leaderId, Long playerId) {
        get(leaderId);
        if (leaderId.equals(playerId)) {
            throw new ApiException(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                    "Un leader ne peut pas etre son propre joueur.");
        }
        User player = userRepository.findById(playerId)
                .orElseThrow(() -> ApiException.notFound("Joueur"));
        if (player.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE, "Ce compte a ete supprime.");
        }
        if (hasRole(player, RoleName.LEADER)) {
            throw new ApiException(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                    "Ce joueur est lui-meme leader : il ne peut pas etre rattache a un autre leader.");
        }
        if (player.getRoles().stream().anyMatch(role -> role.getName().isStaff())) {
            throw new ApiException(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                    "Un compte du personnel ne peut pas etre rattache a un leader.");
        }
        String currentLeader = leaderNameOf(playerId);
        if (currentLeader != null) {
            throw new ApiException(ErrorCode.LEADER_PLAYER_NOT_ELIGIBLE,
                    "Ce joueur est deja rattache au leader " + currentLeader + ".");
        }

        jdbc.update("INSERT INTO leader_players (player_id, leader_id, added_at, added_by_id) VALUES (?, ?, ?, ?)",
                playerId, leaderId, Timestamp.from(Instant.now()), actorId);
        auditService.record(actorId, "LEADER_PLAYER_ADDED", "User", String.valueOf(leaderId),
                null, "joueur " + playerId);
    }

    @Transactional
    public void removePlayer(Long actorId, Long leaderId, Long playerId) {
        int removed = jdbc.update("DELETE FROM leader_players WHERE leader_id = ? AND player_id = ?",
                leaderId, playerId);
        if (removed == 0) {
            throw ApiException.notFound("Joueur de ce leader");
        }
        auditService.record(actorId, "LEADER_PLAYER_REMOVED", "User", String.valueOf(leaderId),
                "joueur " + playerId, null);
    }

    // --- Outils -------------------------------------------------------------------

    private static BigDecimal validPercent(BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0
                || percent.stripTrailingZeros().scale() > 2) {
            throw new ApiException(ErrorCode.LEADER_COMMISSION_INVALID);
        }
        return percent.setScale(2);
    }

    /** Nom du leader auquel ce joueur est rattache, ou null. */
    private String leaderNameOf(Long playerId) {
        return jdbc.query("""
                SELECT CONCAT(l.first_name, ' ', l.last_name) FROM leader_players lp
                JOIN users l ON l.id = lp.leader_id WHERE lp.player_id = ?
                """, (rs, row) -> rs.getString(1), playerId).stream().findFirst().orElse(null);
    }

    private static boolean hasRole(User user, RoleName name) {
        return user.getRoles().stream().anyMatch(role -> role.getName() == name);
    }

    private static Timestamp since30Days() {
        return Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static LeaderSummary summary(ResultSet rs) throws SQLException {
        return new LeaderSummary(
                rs.getLong("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("status"),
                rs.getBigDecimal("commission_percent"),
                rs.getInt("signup_bonus_credits"),
                rs.getLong("player_count"),
                rs.getLong("last_30_days"),
                rs.getLong("total"),
                rs.getLong("transfers_last_30_days"),
                rs.getLong("transfers_total"));
    }
}
