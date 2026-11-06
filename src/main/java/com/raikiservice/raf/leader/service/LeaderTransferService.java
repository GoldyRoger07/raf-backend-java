package com.raikiservice.raf.leader.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferConfig;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferPlayer;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferResponse;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recharge d'un joueur par son leader : le leader cede des credits de son propre
 * solde a l'un de ses joueurs rattaches. La plateforme ne cree aucun credit, elle
 * le deplace d'un portefeuille a l'autre : le leader est paye par le joueur en
 * dehors de l'application.
 *
 * <p>Regles : fonction active (back-office), joueur rattache a ce leader et actif,
 * montant entre les bornes reglees, solde du leader suffisant. La recharge est
 * irreversible pour le leader ; seul un administrateur peut la corriger par un
 * ajustement.
 *
 * <p>Toutes les lectures passent par JDBC : le debit doit etre la premiere lecture
 * JPA du portefeuille dans la transaction (voir {@link WalletService}).
 */
@Service
@RequiredArgsConstructor
public class LeaderTransferService {

    private static final long DEFAULT_MIN = 10;
    private static final long DEFAULT_MAX = 5000;

    private final JdbcTemplate jdbc;
    private final WalletService walletService;
    private final SettingsService settingsService;
    private final PlayerAppService playerAppService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public TransferConfig config(Long leaderId) {
        Long balance = jdbc.query("SELECT credit_balance FROM wallets WHERE user_id = ?",
                (rs, row) -> rs.getLong(1), leaderId).stream().findFirst().orElse(0L);
        return new TransferConfig(playerAppService.leaderTransferEnabled(), minCredits(), maxCredits(),
                balance);
    }

    /** Joueurs actifs rattaches au leader, recherche par nom ou par chiffres du telephone. */
    @Transactional(readOnly = true)
    public Page<TransferPlayer> players(Long leaderId, String query, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE lp.leader_id = ? AND u.status = 'ACTIVE'");
        List<Object> args = new ArrayList<>(List.of(leaderId));
        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%";
            String digits = query.replaceAll("[^0-9]", "");
            where.append(" AND (LOWER(CONCAT(u.first_name, ' ', u.last_name)) LIKE ?"
                    + " OR LOWER(CONCAT(u.last_name, ' ', u.first_name)) LIKE ?");
            args.add(term);
            args.add(term);
            if (digits.length() >= 3) {
                where.append(" OR u.phone LIKE ?");
                args.add("%" + digits + "%");
            }
            where.append(")");
        }

        String from = " FROM leader_players lp JOIN users u ON u.id = lp.player_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(List.of(leaderId));
        pageArgs.addAll(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        // Les joueurs recharges le plus recemment d'abord : ce sont ceux qu'on recharge le plus.
        List<TransferPlayer> content = jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, u.phone, t.created_at, t.credits
                """ + from + """
                 LEFT JOIN leader_credit_transfers t ON t.id = (
                     SELECT MAX(t2.id) FROM leader_credit_transfers t2
                     WHERE t2.leader_id = ? AND t2.player_id = u.id)
                """ + where + """
                 ORDER BY t.created_at IS NULL, t.created_at DESC, u.first_name, u.last_name, u.id
                 LIMIT ? OFFSET ?
                """,
                (rs, row) -> {
                    Timestamp at = rs.getTimestamp(5);
                    long credits = rs.getLong(6);
                    return new TransferPlayer(rs.getLong(1), rs.getString(2), rs.getString(3),
                            phoneEnd(rs.getString(4)), at == null ? null : at.toInstant(),
                            at == null ? null : credits);
                }, pageArgs.toArray());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> history(Long leaderId, Pageable pageable) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM leader_credit_transfers WHERE leader_id = ?",
                Long.class, leaderId);
        List<TransferResponse> content = jdbc.query("""
                SELECT t.id, t.player_id, u.first_name, u.last_name, t.credits, t.created_at
                FROM leader_credit_transfers t JOIN users u ON u.id = t.player_id
                WHERE t.leader_id = ? ORDER BY t.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new TransferResponse(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant(), null),
                leaderId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /** Back-office : recharges d'un leader, avec son solde apres chacune. */
    @Transactional(readOnly = true)
    public Page<TransferResponse> adminHistory(Long leaderId, Pageable pageable) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM leader_credit_transfers WHERE leader_id = ?",
                Long.class, leaderId);
        List<TransferResponse> content = jdbc.query("""
                SELECT t.id, t.player_id, u.first_name, u.last_name, t.credits, t.created_at, d.balance_after
                FROM leader_credit_transfers t
                JOIN users u ON u.id = t.player_id
                JOIN wallet_transactions d ON d.id = t.debit_transaction_id
                WHERE t.leader_id = ? ORDER BY t.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new TransferResponse(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant(), rs.getLong(7)),
                leaderId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Transactional
    public TransferResponse transfer(Long leaderId, Long playerId, long credits, String requestKey) {
        // Double envoi deja applique : on rend la meme recharge sans rien rejouer.
        Optional<TransferResponse> replay = find(leaderId, requestKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (!playerAppService.leaderTransferEnabled()) {
            throw new ApiException(ErrorCode.LEADER_TRANSFER_DISABLED);
        }
        String leaderName = activeLeaderName(leaderId);
        if (leaderName == null) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Seul un leader peut recharger un joueur.");
        }
        Integer linked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM leader_players lp JOIN users u ON u.id = lp.player_id
                WHERE lp.leader_id = ? AND lp.player_id = ? AND u.status = 'ACTIVE'
                """, Integer.class, leaderId, playerId);
        if (linked == null || linked == 0) {
            throw new ApiException(ErrorCode.LEADER_TRANSFER_NOT_YOUR_PLAYER);
        }
        long min = minCredits();
        long max = maxCredits();
        if (credits < min || credits > max) {
            throw new ApiException(ErrorCode.LEADER_TRANSFER_AMOUNT_INVALID,
                    "Le montant d'une recharge doit etre compris entre " + min + " et " + max + " credits.");
        }

        String key = "leader-transfer:" + leaderId + ":" + requestKey;
        WalletTransaction debit = walletService.debit(leaderId, BalanceType.CREDIT, credits,
                WalletTransactionType.LEADER_TRANSFER_OUT, "User", String.valueOf(playerId),
                key + ":out", "Recharge de " + playerName(playerId));

        // Le debit a verrouille le portefeuille du leader : une requete concurrente portant
        // la meme cle a attendu ici et retrouve la recharge deja enregistree.
        replay = find(leaderId, requestKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        WalletTransaction credit = walletService.credit(playerId, BalanceType.CREDIT, credits,
                WalletTransactionType.LEADER_TRANSFER_IN, "User", String.valueOf(leaderId),
                key + ":in", "Recharge par votre leader " + leaderName);

        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO leader_credit_transfers
                    (leader_id, player_id, credits, debit_transaction_id, credit_transaction_id, request_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, leaderId, playerId, credits, debit.getId(), credit.getId(), requestKey, Timestamp.from(now));

        auditService.record(leaderId, "LEADER_CREDIT_TRANSFER", "User", String.valueOf(playerId),
                null, credits + " credits du leader " + leaderId + " au joueur " + playerId);
        notificationService.notifyUser(playerId, NotificationMessages.leaderTransferReceived(leaderName, credits));

        TransferResponse saved = find(leaderId, requestKey).orElseThrow();
        return new TransferResponse(saved.id(), saved.playerId(), saved.playerFirstName(),
                saved.playerLastName(), saved.credits(), saved.createdAt(), debit.getBalanceAfter());
    }

    // --- Outils -------------------------------------------------------------------

    private Optional<TransferResponse> find(Long leaderId, String requestKey) {
        return jdbc.query("""
                SELECT t.id, t.player_id, u.first_name, u.last_name, t.credits, t.created_at, d.balance_after
                FROM leader_credit_transfers t
                JOIN users u ON u.id = t.player_id
                JOIN wallet_transactions d ON d.id = t.debit_transaction_id
                WHERE t.leader_id = ? AND t.request_key = ?
                """, (rs, row) -> new TransferResponse(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant(), rs.getLong(7)),
                leaderId, requestKey).stream().findFirst();
    }

    /** Nom du leader s'il a bien le role et un compte actif, sinon null. */
    private String activeLeaderName(Long leaderId) {
        return jdbc.query("""
                SELECT CONCAT(u.first_name, ' ', u.last_name) FROM users u
                WHERE u.id = ? AND u.status = 'ACTIVE' AND EXISTS (
                    SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                    WHERE ur.user_id = u.id AND r.name = 'LEADER')
                """, (rs, row) -> rs.getString(1), leaderId).stream().findFirst().orElse(null);
    }

    private String playerName(Long playerId) {
        return jdbc.queryForObject("SELECT CONCAT(first_name, ' ', last_name) FROM users WHERE id = ?",
                String.class, playerId);
    }

    private long minCredits() {
        return settingsService.getLong(SettingKeys.LEADER_TRANSFER_MIN_CREDITS, DEFAULT_MIN);
    }

    private long maxCredits() {
        return settingsService.getLong(SettingKeys.LEADER_TRANSFER_MAX_CREDITS, DEFAULT_MAX);
    }

    private static String phoneEnd(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }
}
