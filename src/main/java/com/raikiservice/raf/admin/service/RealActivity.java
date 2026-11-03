package com.raikiservice.raf.admin.service;

/**
 * Fragments SQL qui ecartent les tests des chiffres d'activite : comptes de test
 * (users.is_test) et quiz de test (quizzes.is_test).
 *
 * <p>Un mouvement du portefeuille remonte a son quiz par sa reference : ticket
 * (achat, remboursement), partie (entree solo, gain sans-faute) ou classement
 * (gain). Les soldes et le rapprochement n'utilisent pas ces fragments : l'argent
 * d'un test reste de l'argent reel.
 */
final class RealActivity {

    /** Mouvements du portefeuille, alias {@code t}, hors tests. */
    static final String TRANSACTIONS = """
            FROM wallet_transactions t
            JOIN wallets w ON w.id = t.wallet_id
            JOIN users u ON u.id = w.user_id
            LEFT JOIN tickets tk ON t.reference_type = 'Ticket' AND tk.id = t.reference_id
            LEFT JOIN game_sessions gs ON t.reference_type = 'GameSession' AND gs.id = t.reference_id
            LEFT JOIN quiz_rankings qr ON t.reference_type = 'QuizRanking' AND qr.id = t.reference_id
            LEFT JOIN quizzes q ON q.id = COALESCE(tk.quiz_id, gs.quiz_id, qr.quiz_id)
            WHERE u.is_test = FALSE AND COALESCE(q.is_test, FALSE) = FALSE
            """;

    /** Tickets, alias {@code tk}, hors tests. */
    static final String TICKETS = """
            FROM tickets tk
            JOIN users u ON u.id = tk.user_id
            JOIN quizzes q ON q.id = tk.quiz_id
            WHERE u.is_test = FALSE AND q.is_test = FALSE
            """;

    /** Parties, alias {@code gs}, hors tests. */
    static final String SESSIONS = """
            FROM game_sessions gs
            JOIN users u ON u.id = gs.user_id
            JOIN quizzes q ON q.id = gs.quiz_id
            WHERE u.is_test = FALSE AND q.is_test = FALSE
            """;

    /** Demandes de retrait, alias {@code wr}, hors comptes de test. */
    static final String WITHDRAWALS = """
            FROM withdrawal_requests wr
            JOIN users u ON u.id = wr.user_id
            WHERE u.is_test = FALSE
            """;

    private RealActivity() {
    }
}
