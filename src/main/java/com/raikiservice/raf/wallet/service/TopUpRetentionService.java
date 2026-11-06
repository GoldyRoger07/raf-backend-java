package com.raikiservice.raf.wallet.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.wallet.repository.WalletRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retenue de recharge : les gourdes d'une recharge qui ne forment pas un credit
 * entier, gardees pour le joueur et ajoutees a sa recharge suivante.
 *
 * <p>Tenue comme un grand livre (table {@code topup_retentions}) : une ligne par
 * mouvement, le solde est celui de la ligne la plus recente. Chaque mouvement se
 * fait sous le verrou de la ligne du portefeuille, pris par JDBC : il serialise deux
 * recharges simultanees du meme joueur sans charger l'entite JPA (le debit ou le
 * credit qui suit doit rester la premiere lecture JPA du portefeuille, voir
 * {@link WalletService}).
 */
@Service
@RequiredArgsConstructor
public class TopUpRetentionService {

    public enum Reason {
        /** Reste d'une recharge, mis de cote. */
        KEPT,
        /** Reutilisee par une recharge. */
        USED,
        /** Correction par un administrateur. */
        ADJUSTMENT
    }

    public record Entry(Long id, long amountHtg, long balanceAfterHtg, Reason reason, Long topUpRequestId,
            Long adminId, String note, Instant createdAt) {
    }

    public record View(long balanceHtg, long htgRate, List<Entry> entries) {
    }

    private final JdbcTemplate jdbc;
    private final WalletRepository walletRepository;
    private final WalletProvisioner walletProvisioner;
    private final WalletService walletService;
    private final AuditService auditService;

    /** Verrouille la retenue du joueur jusqu'a la fin de la transaction et renvoie son solde. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long lock(Long userId) {
        if (!walletRepository.existsByUserId(userId)) {
            walletProvisioner.createIfAbsent(userId);
        }
        jdbc.query("SELECT id FROM wallets WHERE user_id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), userId);
        return balance(userId);
    }

    /** Enregistre un mouvement ; la retenue doit avoir ete verrouillee par {@link #lock}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long move(Long userId, long amountHtg, Reason reason, Long topUpRequestId, Long adminId, String note) {
        long after = balance(userId) + amountHtg;
        if (after < 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "La retenue ne peut pas devenir negative (solde actuel : " + balance(userId) + " HTG).");
        }
        jdbc.update("""
                INSERT INTO topup_retentions
                    (user_id, amount_htg, balance_after_htg, reason, topup_request_id, admin_id, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, amountHtg, after, reason.name(), topUpRequestId, adminId, note,
                Timestamp.from(Instant.now()));
        return after;
    }

    @Transactional(readOnly = true)
    public long balance(Long userId) {
        return jdbc.query("SELECT balance_after_htg FROM topup_retentions WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getLong(1), userId).stream().findFirst().orElse(0L);
    }

    /** Back-office : solde et derniers mouvements. */
    @Transactional(readOnly = true)
    public View view(Long userId, int limit) {
        List<Entry> entries = jdbc.query("""
                SELECT id, amount_htg, balance_after_htg, reason, topup_request_id, admin_id, note, created_at
                FROM topup_retentions WHERE user_id = ? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> {
                    long topUp = rs.getLong(5);
                    Long topUpId = rs.wasNull() ? null : topUp;
                    long admin = rs.getLong(6);
                    Long adminId = rs.wasNull() ? null : admin;
                    return new Entry(rs.getLong(1), rs.getLong(2), rs.getLong(3), Reason.valueOf(rs.getString(4)),
                            topUpId, adminId, rs.getString(7), rs.getTimestamp(8).toInstant());
                }, userId, limit);
        return new View(entries.isEmpty() ? 0 : entries.get(0).balanceAfterHtg(), walletService.htgRate(), entries);
    }

    /**
     * Correction par un administrateur (erreur de saisie, remboursement en especes) :
     * montant signe, motif obligatoire, trace au journal d'audit.
     */
    @Transactional
    public long adjust(Long adminId, Long userId, long signedAmountHtg, String reason) {
        if (signedAmountHtg == 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "Le montant ne peut pas etre nul.");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "Le motif est obligatoire.");
        }
        lock(userId);
        long after = move(userId, signedAmountHtg, Reason.ADJUSTMENT, null, adminId, reason.trim());
        auditService.record(adminId, "TOPUP_RETENTION_ADJUSTED", "User", String.valueOf(userId), null,
                signedAmountHtg + " HTG sur la retenue (solde " + after + " HTG) : " + reason.trim());
        return after;
    }
}
