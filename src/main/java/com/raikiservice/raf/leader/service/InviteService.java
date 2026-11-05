package com.raikiservice.raf.leader.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.InviteResponse;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lien d'invitation d'un joueur : {@code <app>/inscription?parrain=<id>}.
 *
 * <ul>
 *   <li>Le lien d'un joueur ordinaire mene a une inscription normale.</li>
 *   <li>Celui d'un leader (role LEADER, compte actif, hors test) rattache le nouvel
 *       inscrit a ce leader, sauf si un administrateur l'a deja rattache ailleurs.</li>
 *   <li>Le bonus fixe pour ce leader au moment de l'inscription est promis au nouvel
 *       inscrit et lui est verse, en credits offerts par la plateforme, des qu'il
 *       confirme son email. Une seule fois par joueur.</li>
 * </ul>
 *
 * <p>Un parrain inconnu ou non eligible ne fait jamais echouer une inscription.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InviteService {

    /** Le leader, s'il peut recevoir des filleuls : role LEADER, compte actif, hors test. */
    private static final String ELIGIBLE_LEADER = """
            SELECT u.id, COALESCE(pr.signup_bonus_credits, 0) AS bonus
            FROM users u
            LEFT JOIN leader_profiles pr ON pr.user_id = u.id
            WHERE u.id = ? AND u.status = 'ACTIVE' AND u.is_test = FALSE
              AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                          WHERE ur.user_id = u.id AND r.name = 'LEADER')
            """;

    private final JdbcTemplate jdbc;
    private final WalletService walletService;
    private final AuditService auditService;
    private final AppProperties appProperties;

    private record PendingBonus(Long leaderId, int credits) {
    }

    /** Lien a partager et, pour un leader, le bonus que recevront ses filleuls. */
    @Transactional(readOnly = true)
    public InviteResponse inviteFor(Long userId) {
        String url = appProperties.frontendUrl() + "/inscription?parrain=" + userId;
        List<Integer> bonus = jdbc.query(ELIGIBLE_LEADER, (rs, row) -> rs.getInt("bonus"), userId);
        return bonus.isEmpty()
                ? new InviteResponse(url, false, 0)
                : new InviteResponse(url, true, bonus.get(0));
    }

    /**
     * A l'inscription, dans sa transaction : rattache le nouvel inscrit au leader
     * qui l'a invite. Sans effet si le parrain n'est pas un leader eligible.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachOnSignup(User player, Long inviterId) {
        if (inviterId == null || inviterId.equals(player.getId())) {
            return;
        }
        List<Integer> bonus = jdbc.query(ELIGIBLE_LEADER, (rs, row) -> rs.getInt("bonus"), inviterId);
        if (bonus.isEmpty()) {
            return;
        }
        int credits = bonus.get(0);
        jdbc.update("""
                INSERT INTO leader_players (player_id, leader_id, added_at, added_by_id, via_invite,
                                            signup_bonus_credits)
                VALUES (?, ?, ?, NULL, TRUE, ?)
                """, player.getId(), inviterId, Timestamp.from(Instant.now()), credits);
        auditService.record(player.getId(), "LEADER_PLAYER_INVITED", "User", String.valueOf(inviterId),
                null, "joueur " + player.getId() + (credits > 0 ? ", bonus promis " + credits + " credits" : ""));
    }

    /**
     * A la confirmation de l'email, dans sa transaction : verse le bonus promis.
     * Sans effet si l'email n'est pas confirme ou si le bonus a deja ete verse.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payBonusIfEligible(User player) {
        if (!player.isEmailVerified() || player.getStatus() != UserStatus.ACTIVE || player.isTest()) {
            return;
        }
        List<PendingBonus> pending = jdbc.query("""
                SELECT lp.leader_id, lp.signup_bonus_credits
                FROM leader_players lp
                WHERE lp.player_id = ? AND lp.via_invite = TRUE AND lp.signup_bonus_credits > 0
                  AND NOT EXISTS (SELECT 1 FROM leader_invite_bonuses b WHERE b.player_id = lp.player_id)
                """, (rs, row) -> new PendingBonus(rs.getLong("leader_id"), rs.getInt("signup_bonus_credits")),
                player.getId());
        if (pending.isEmpty()) {
            return;
        }

        PendingBonus bonus = pending.get(0);
        WalletTransaction credit = walletService.credit(player.getId(), BalanceType.CREDIT, bonus.credits(),
                WalletTransactionType.INVITE_BONUS, "User", String.valueOf(bonus.leaderId()),
                "invite-bonus:player:" + player.getId(),
                "Bonus de bienvenue - invitation d'un leader");
        jdbc.update("""
                INSERT INTO leader_invite_bonuses (player_id, leader_id, credits, transaction_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, player.getId(), bonus.leaderId(), bonus.credits(), credit.getId(),
                Timestamp.from(Instant.now()));
        auditService.record(null, "LEADER_INVITE_BONUS_PAID", "User", String.valueOf(player.getId()),
                null, bonus.credits() + " credits (leader " + bonus.leaderId() + ")");
        log.info("Bonus d'invitation de {} credits verse au joueur {} (leader {})",
                bonus.credits(), player.getId(), bonus.leaderId());
    }
}
