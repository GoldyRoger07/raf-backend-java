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
        // Un ticket ou une partie solo offerts (prix a 0) n'ont aucun mouvement.
        queries.add(link(ReconciliationCheck.TICKET_DEBIT, "tickets", "Ticket", "transaction_id",
                "b.price_paid_credits > 0", "TICKET_PURCHASE", "CREDIT", "-b.price_paid_credits"));
        queries.add(link(ReconciliationCheck.TICKET_REFUND, "tickets", "Ticket",
                "refund_transaction_id", "b.status = 'REFUNDED' AND b.price_paid_credits > 0", "REFUND",
                "CREDIT", "b.price_paid_credits"));
        // Une recharge trop petite pour un credit (tout part en retenue) n'a aucun mouvement.
        queries.add(link(ReconciliationCheck.TOPUP_CREDIT, "topup_requests", "TopUpRequest",
                "transaction_id", "b.status = 'CONFIRMED' AND b.credits > 0", "TOPUP", "CREDIT", "b.credits"));
        // Recharge avec les gains : payee par un debit des gains du meme montant.
        queries.add(link(ReconciliationCheck.WINNINGS_CONVERSION_DEBIT, "topup_requests", "TopUpRequest",
                "debit_transaction_id", "b.provider = 'WINNINGS'", "WINNINGS_CONVERSION", "WINNINGS",
                "-b.amount_htg"));

        // Retenue : credits * taux = montant recu + retenue reprise - retenue gardee, et
        // les lignes de la retenue rattachees a la recharge en font le meme bilan.
        queries.add(new Query(ReconciliationCheck.TOPUP_RETENTION, """
                SELECT b.user_id, NULL AS wallet_id, 'TopUpRequest' AS reference_type, b.id AS reference_id,
                       b.amount_htg + b.retention_used_htg - b.retention_kept_htg AS expected_value,
                       b.credits * b.htg_rate AS actual_value,
                       CASE WHEN b.credits * b.htg_rate <> b.amount_htg + b.retention_used_htg - b.retention_kept_htg
                            THEN 'Credits differents du montant recu, retenue comprise'
                            ELSE 'Mouvements de retenue differents de la recharge' END AS detail
                FROM topup_requests b
                LEFT JOIN (SELECT topup_request_id, SUM(amount_htg) AS net FROM topup_retentions
                           WHERE topup_request_id IS NOT NULL GROUP BY topup_request_id) r ON r.topup_request_id = b.id
                WHERE b.status = 'CONFIRMED' AND b.htg_rate IS NOT NULL
                  AND (b.credits * b.htg_rate <> b.amount_htg + b.retention_used_htg - b.retention_kept_htg
                       OR COALESCE(r.net, 0) <> b.retention_kept_htg - b.retention_used_htg)
                """));
        queries.add(new Query(ReconciliationCheck.RETENTION_CHAIN_BREAK, """
                SELECT x.user_id, NULL AS wallet_id, 'TopUpRetention' AS reference_type, x.id AS reference_id,
                       x.prev + x.amount_htg AS expected_value, x.balance_after_htg AS actual_value,
                       'Solde de retenue incoherent' AS detail
                FROM (SELECT r.id, r.user_id, r.amount_htg, r.balance_after_htg,
                             LAG(r.balance_after_htg, 1, 0) OVER (PARTITION BY r.user_id ORDER BY r.id) AS prev
                      FROM topup_retentions r) x
                WHERE x.prev + x.amount_htg <> x.balance_after_htg
                """));
        queries.add(link(ReconciliationCheck.WITHDRAWAL_DEBIT, "withdrawal_requests",
                "WithdrawalRequest", "debit_transaction_id",
                "b.status IN ('PENDING', 'APPROVED', 'SENDING', 'PAID', 'REJECTED')", "WITHDRAWAL", "WINNINGS",
                "-b.amount_htg"));
        queries.add(link(ReconciliationCheck.WITHDRAWAL_REFUND, "withdrawal_requests",
                "WithdrawalRequest", "refund_transaction_id", "b.status = 'REJECTED'", "REFUND",
                "WINNINGS", "b.amount_htg"));
        queries.add(link(ReconciliationCheck.PRIZE_PAYMENT, "quiz_rankings", "QuizRanking",
                "transaction_id", "b.reward_htg > 0", "PRIZE", "WINNINGS", "b.reward_htg"));
        queries.add(link(ReconciliationCheck.SOLO_ENTRY_DEBIT, "game_sessions", "GameSession",
                "entry_transaction_id", "b.game_mode = 'SOLO' AND b.entry_price_credits > 0", "SOLO_ENTRY",
                "CREDIT", "-b.entry_price_credits"));
        queries.add(link(ReconciliationCheck.SOLO_PRIZE_PAYMENT, "game_sessions", "GameSession",
                "prize_transaction_id", "b.won = TRUE", "PRIZE", "WINNINGS", "b.prize_htg"));
        // Recharge par un leader : le debit est sur le portefeuille du leader, le
        // credit sur celui du joueur. La table expose le bon titulaire en user_id.
        queries.add(link(ReconciliationCheck.LEADER_TRANSFER_DEBIT,
                "(SELECT id, leader_id AS user_id, credits, debit_transaction_id FROM leader_credit_transfers)",
                "LeaderCreditTransfer", "debit_transaction_id", "TRUE", "LEADER_TRANSFER_OUT", "CREDIT",
                "-b.credits"));
        queries.add(link(ReconciliationCheck.LEADER_TRANSFER_CREDIT,
                "(SELECT id, player_id AS user_id, credits, credit_transaction_id FROM leader_credit_transfers)",
                "LeaderCreditTransfer", "credit_transaction_id", "TRUE", "LEADER_TRANSFER_IN", "CREDIT",
                "b.credits"));

        // Un mouvement la ou il ne devait pas y en avoir.
        queries.add(unexpected("tickets", "Ticket", "refund_transaction_id",
                "b.status <> 'REFUNDED'", "Ticket non rembourse portant un remboursement"));
        queries.add(unexpected("tickets", "Ticket", "transaction_id",
                "b.price_paid_credits = 0", "Ticket offert portant un debit"));
        queries.add(unexpected("tickets", "Ticket", "refund_transaction_id",
                "b.price_paid_credits = 0", "Ticket offert portant un remboursement"));
        queries.add(unexpected("game_sessions", "GameSession", "entry_transaction_id",
                "b.entry_price_credits = 0", "Partie solo offerte portant un debit"));
        queries.add(unexpected("topup_requests", "TopUpRequest", "transaction_id",
                "b.status <> 'CONFIRMED'", "Recharge non confirmee portant un credit"));
        queries.add(unexpected("topup_requests", "TopUpRequest", "transaction_id",
                "b.credits = 0", "Recharge sans credit portant un mouvement"));
        queries.add(unexpected("topup_requests", "TopUpRequest", "debit_transaction_id",
                "b.provider <> 'WINNINGS'", "Recharge payee hors gains portant un debit des gains"));
        queries.add(unexpected("withdrawal_requests", "WithdrawalRequest", "debit_transaction_id",
                "b.status IN ('AWAITING_OTP', 'CANCELLED')",
                "Retrait non confirme ou annule portant un debit"));
        queries.add(unexpected("withdrawal_requests", "WithdrawalRequest", "refund_transaction_id",
                "b.status <> 'REJECTED'", "Retrait non rejete portant une restitution"));
        queries.add(unexpected("quiz_rankings", "QuizRanking", "transaction_id",
                "b.reward_htg = 0", "Rang non recompense portant un versement"));
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
                   OR (t.type = 'WINNINGS_CONVERSION'
                        AND NOT EXISTS (SELECT 1 FROM topup_requests b WHERE b.debit_transaction_id = t.id))
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
                   OR (t.type = 'COMMISSION'
                        AND NOT EXISTS (SELECT 1 FROM leader_commissions b WHERE b.transaction_id = t.id))
                   OR (t.type = 'INVITE_BONUS'
                        AND NOT EXISTS (SELECT 1 FROM leader_invite_bonuses b WHERE b.transaction_id = t.id))
                   OR (t.type = 'LEADER_TRANSFER_OUT'
                        AND NOT EXISTS (SELECT 1 FROM leader_credit_transfers b
                                        WHERE b.debit_transaction_id = t.id))
                   OR (t.type = 'LEADER_TRANSFER_IN'
                        AND NOT EXISTS (SELECT 1 FROM leader_credit_transfers b
                                        WHERE b.credit_transaction_id = t.id))
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
