package com.raikiservice.raf.reconciliation.service;

import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.reconciliation.domain.ReconciliationCheck;

/**
 * Les requetes du rapprochement, en SQL natif.
 *
 * <p>Chaque requete renvoie les memes colonnes ({@code user_id, wallet_id,
 * reference_type, reference_id, expected_value, actual_value, detail}) : une ligne
 * = un ecart.
 *
 * <p><strong>Pas de faux positif en pleine activite.</strong> Chaque controle tient
 * en une seule instruction, et InnoDB lit une instruction sur un instantane
 * coherent. Or une operation metier et son mouvement d'argent sont toujours
 * commites ensemble (ticket et debit, partie solo et debit d'entree, sans-faute et
 * prix, retrait et gel, classement et gain) : une
 * instruction voit les deux, ou aucun des deux.
 */
final class ReconciliationQueries {

    record Query(ReconciliationCheck check, String sql) {
    }

    private ReconciliationQueries() {
    }

    static List<Query> all() {
        List<Query> queries = new ArrayList<>();

        queries.add(ledger("CREDIT", "credit_balance"));
        queries.add(ledger("WINNINGS", "winnings_balance"));
        queries.add(new Query(ReconciliationCheck.BALANCE_CHAIN_BREAK, """
                SELECT w.user_id, x.wallet_id, 'WalletTransaction' AS reference_type,
                       x.id AS reference_id, x.prev + x.amount AS expected_value,
                       x.balance_after AS actual_value,
                       CONCAT('Solde ', x.balance_type, ' apres le mouvement ', x.type) AS detail
                FROM (SELECT t.id, t.wallet_id, t.type, t.balance_type, t.amount, t.balance_after,
                             LAG(t.balance_after, 1, 0) OVER (
                                 PARTITION BY t.wallet_id, t.balance_type ORDER BY t.id) AS prev
                      FROM wallet_transactions t) x
                JOIN wallets w ON w.id = x.wallet_id
                WHERE x.prev + x.amount <> x.balance_after
                """));

        // Chaque operation metier doit pointer vers le bon mouvement : bon type, bon
        // solde, bon montant, et le portefeuille de son proprietaire.
        queries.add(link(ReconciliationCheck.TICKET_DEBIT, "tickets", "Ticket", "transaction_id",
                "TRUE", "TICKET_PURCHASE", "CREDIT", "-b.price_paid_credits"));
        queries.add(link(ReconciliationCheck.TICKET_REFUND, "tickets", "Ticket",
                "refund_transaction_id", "b.status = 'REFUNDED'", "REFUND", "CREDIT",
                "b.price_paid_credits"));
        queries.add(link(ReconciliationCheck.TOPUP_CREDIT, "topup_requests", "TopUpRequest",
                "transaction_id", "b.status = 'CONFIRMED'", "TOPUP", "CREDIT", "b.credits"));
        queries.add(link(ReconciliationCheck.WITHDRAWAL_DEBIT, "withdrawal_requests",
                "WithdrawalRequest", "debit_transaction_id",
                "b.status IN ('PENDING', 'APPROVED', 'PAID', 'REJECTED')", "WITHDRAWAL", "WINNINGS",
                "-b.amount_credits"));
        queries.add(link(ReconciliationCheck.WITHDRAWAL_REFUND, "withdrawal_requests",
                "WithdrawalRequest", "refund_transaction_id", "b.status = 'REJECTED'", "REFUND",
                "WINNINGS", "b.amount_credits"));
        queries.add(link(ReconciliationCheck.PRIZE_PAYMENT, "quiz_rankings", "QuizRanking",
                "transaction_id", "b.reward_credits > 0", "PRIZE", "WINNINGS", "b.reward_credits"));
        queries.add(link(ReconciliationCheck.SOLO_ENTRY_DEBIT, "game_sessions", "GameSession",
                "entry_transaction_id", "b.game_mode = 'SOLO'", "SOLO_ENTRY", "CREDIT",
                "-b.entry_price_credits"));
        queries.add(link(ReconciliationCheck.SOLO_PRIZE_PAYMENT, "game_sessions", "GameSession",
                "prize_transaction_id", "b.won = TRUE", "PRIZE", "WINNINGS", "b.prize_credits"));

        // Un mouvement la ou il ne devait pas y en avoir.
        queries.add(unexpected("tickets", "Ticket", "refund_transaction_id",
                "b.status <> 'REFUNDED'", "Ticket non rembourse portant un remboursement"));
        queries.add(unexpected("topup_requests", "TopUpRequest", "transaction_id",
                "b.status <> 'CONFIRMED'", "Recharge non confirmee portant un credit"));
        queries.add(unexpected("withdrawal_requests", "WithdrawalRequest", "debit_transaction_id",
                "b.status IN ('AWAITING_OTP', 'CANCELLED')",
                "Retrait non confirme ou annule portant un debit"));
        queries.add(unexpected("withdrawal_requests", "WithdrawalRequest", "refund_transaction_id",
                "b.status <> 'REJECTED'", "Retrait non rejete portant une restitution"));
        queries.add(unexpected("quiz_rankings", "QuizRanking", "transaction_id",
                "b.reward_credits = 0", "Rang non recompense portant un versement"));
        queries.add(unexpected("game_sessions", "GameSession", "prize_transaction_id",
                "b.won = FALSE", "Partie solo perdue portant un versement"));

        // Dans l'autre sens : de l'argent a bouge sans operation qui l'explique. Les
        // ajustements (ADJUSTMENT) sont exclus : ils n'ont pas d'operation metier,
        // leur justification est dans le journal d'audit.
        queries.add(new Query(ReconciliationCheck.ORPHAN_TRANSACTION, """
                SELECT w.user_id, t.wallet_id, 'WalletTransaction' AS reference_type,
                       t.id AS reference_id, NULL AS expected_value, t.amount AS actual_value,
                       CONCAT('Mouvement ', t.type, ' sans operation qui le reference') AS detail
                FROM wallet_transactions t
                JOIN wallets w ON w.id = t.wallet_id
                WHERE (t.type = 'TICKET_PURCHASE'
                        AND NOT EXISTS (SELECT 1 FROM tickets b WHERE b.transaction_id = t.id))
                   OR (t.type = 'TOPUP'
                        AND NOT EXISTS (SELECT 1 FROM topup_requests b WHERE b.transaction_id = t.id))
                   OR (t.type = 'WITHDRAWAL'
                        AND NOT EXISTS (SELECT 1 FROM withdrawal_requests b
                                        WHERE b.debit_transaction_id = t.id))
                   OR (t.type = 'SOLO_ENTRY'
                        AND NOT EXISTS (SELECT 1 FROM game_sessions b
                                        WHERE b.entry_transaction_id = t.id))
                   OR (t.type = 'PRIZE'
                        AND NOT EXISTS (SELECT 1 FROM quiz_rankings b WHERE b.transaction_id = t.id)
                        AND NOT EXISTS (SELECT 1 FROM game_sessions b
                                        WHERE b.prize_transaction_id = t.id))
                   OR (t.type = 'REFUND'
                        AND NOT EXISTS (SELECT 1 FROM tickets b WHERE b.refund_transaction_id = t.id)
                        AND NOT EXISTS (SELECT 1 FROM withdrawal_requests b
                                        WHERE b.refund_transaction_id = t.id))
                """));

        return queries;
    }

    private static Query ledger(String balanceType, String column) {
        return new Query(ReconciliationCheck.LEDGER_MISMATCH, """
                SELECT w.user_id, w.id AS wallet_id, 'Wallet' AS reference_type,
                       w.id AS reference_id, l.ledger AS expected_value, w.%2$s AS actual_value,
                       'Solde %1$s different de la somme de ses transactions' AS detail
                FROM wallets w
                JOIN (SELECT w2.id, COALESCE(SUM(t.amount), 0) AS ledger
                      FROM wallets w2
                      LEFT JOIN wallet_transactions t
                             ON t.wallet_id = w2.id AND t.balance_type = '%1$s'
                      GROUP BY w2.id) l ON l.id = w.id
                WHERE l.ledger <> w.%2$s
                """.formatted(balanceType, column));
    }

    private static Query link(ReconciliationCheck check, String table, String referenceType,
            String txColumn, String condition, String txType, String balanceType,
            String expectedAmount) {
        return new Query(check, """
                SELECT b.user_id, tx.wallet_id, '%2$s' AS reference_type, b.id AS reference_id,
                       %7$s AS expected_value, tx.amount AS actual_value,
                       CASE
                           WHEN tx.id IS NULL THEN 'Aucun mouvement associe'
                           WHEN tx.type <> '%5$s' THEN CONCAT('Mouvement de type ', tx.type)
                           WHEN tx.balance_type <> '%6$s' THEN CONCAT('Mouvement sur le solde ', tx.balance_type)
                           WHEN w.user_id <> b.user_id THEN 'Mouvement sur le portefeuille d''un autre joueur'
                           ELSE 'Montant different'
                       END AS detail
                FROM %1$s b
                LEFT JOIN wallet_transactions tx ON tx.id = b.%3$s
                LEFT JOIN wallets w ON w.id = tx.wallet_id
                WHERE (%4$s)
                  AND (tx.id IS NULL
                       OR tx.type <> '%5$s'
                       OR tx.balance_type <> '%6$s'
                       OR tx.amount <> %7$s
                       OR w.user_id <> b.user_id)
                """.formatted(table, referenceType, txColumn, condition, txType, balanceType,
                expectedAmount));
    }

    private static Query unexpected(String table, String referenceType, String txColumn,
            String condition, String detail) {
        return new Query(ReconciliationCheck.UNEXPECTED_MOVEMENT, """
                SELECT b.user_id, tx.wallet_id, '%2$s' AS reference_type, b.id AS reference_id,
                       NULL AS expected_value, tx.amount AS actual_value, '%5$s' AS detail
                FROM %1$s b
                LEFT JOIN wallet_transactions tx ON tx.id = b.%3$s
                WHERE (%4$s) AND b.%3$s IS NOT NULL
                """.formatted(table, referenceType, txColumn, condition, detail));
    }
}
