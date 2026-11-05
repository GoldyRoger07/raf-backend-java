package com.raikiservice.raf.leader.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.quiz.domain.Quiz;
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
 * Commissions des leaders : quand un joueur rattache a un leader perd, le leader
 * touche un pourcentage du prix paye, en HTG, sur son solde des gains.
 *
 * <ul>
 *   <li>Quiz en groupe : a la cloture, un joueur qui n'a rien gagne a perdu ; la
 *       commission porte sur tous ses tickets non rembourses (joues ou non). Un seul
 *       credit par leader et par quiz, detaille par joueur dans leader_commissions.</li>
 *   <li>Solo : chaque partie payee qui n'est pas un sans-faute donne une commission
 *       sur son prix d'entree. Une partie abandonnee est close par le meme chemin
 *       (les parties solo sont sequentielles) et compte donc comme perdue.</li>
 * </ul>
 *
 * <p>Jamais de commission sur un quiz ou un compte de test, sur un quiz gratuit, ni
 * pour un leader qui n'a plus le role ou dont le compte n'est pas actif. Montant :
 * credits x taux x pourcentage, arrondi a la gourde inferieure ; a 0 rien ne part.
 * Le pourcentage et le taux du moment sont figes dans chaque ligne.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    /** Leader du joueur, s'il est eligible : role LEADER, compte actif, hors test. */
    private static final String ELIGIBLE_LEADER = """
            SELECT lp.player_id, lp.leader_id, COALESCE(pr.commission_percent, 0) AS commission_percent
            FROM leader_players lp
            JOIN users l ON l.id = lp.leader_id
            LEFT JOIN leader_profiles pr ON pr.user_id = lp.leader_id
            WHERE l.status = 'ACTIVE' AND l.is_test = FALSE
              AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                          WHERE ur.user_id = l.id AND r.name = 'LEADER')
            """;

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    private record Line(Long leaderId, Long playerId, String sourceKey, long baseCredits,
            BigDecimal percent, long amountHtg) {
    }

    /**
     * A la cloture d'un quiz en groupe, dans la transaction qui verse les gains.
     *
     * @param winnerIds joueurs ayant touche un gain sur ce quiz : ils n'ont pas perdu
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payQuizCommissions(Quiz quiz, Set<Long> winnerIds) {
        if (quiz.isTest() || quiz.getTicketPriceCredits() <= 0) {
            return;
        }
        long rate = walletService.htgRate();

        // Credits depenses par joueur sur ce quiz, tickets rembourses et de test exclus,
        // pour les seuls joueurs dont le leader est eligible.
        List<Line> lines = new ArrayList<>();
        jdbc.query("""
                SELECT el.leader_id, el.player_id, el.commission_percent, SUM(tk.price_paid_credits) AS spent
                FROM tickets tk
                JOIN (%s) el ON el.player_id = tk.user_id
                JOIN users p ON p.id = tk.user_id
                WHERE tk.quiz_id = ? AND tk.status <> 'REFUNDED' AND tk.is_test = FALSE
                  AND p.is_test = FALSE
                GROUP BY el.leader_id, el.player_id, el.commission_percent
                ORDER BY el.leader_id, el.player_id
                """.formatted(ELIGIBLE_LEADER), rs -> {
            Long playerId = rs.getLong("player_id");
            if (winnerIds.contains(playerId)) {
                return;
            }
            long spent = rs.getLong("spent");
            BigDecimal percent = rs.getBigDecimal("commission_percent");
            long amount = amount(spent, rate, percent);
            if (amount > 0) {
                lines.add(new Line(rs.getLong("leader_id"), playerId,
                        "quiz:" + quiz.getId() + ":player:" + playerId, spent, percent, amount));
            }
        }, quiz.getId());

        // Un credit par leader, dans l'ordre des identifiants : les verrous de
        // portefeuille sont toujours pris dans le meme ordre.
        Map<Long, List<Line>> byLeader = new TreeMap<>();
        for (Line line : lines) {
            byLeader.computeIfAbsent(line.leaderId(), id -> new ArrayList<>()).add(line);
        }
        byLeader.forEach((leaderId, leaderLines) -> {
            long total = leaderLines.stream().mapToLong(Line::amountHtg).sum();
            WalletTransaction credit = walletService.credit(leaderId, BalanceType.WINNINGS, total,
                    WalletTransactionType.COMMISSION, "Quiz", String.valueOf(quiz.getId()),
                    "commission:quiz:" + quiz.getId() + ":leader:" + leaderId,
                    "Commission - " + quiz.getTitle() + " (" + leaderLines.size() + " joueur(s))");
            for (Line line : leaderLines) {
                insert(line, "QUIZ", quiz.getId(), null, rate, credit.getId());
            }
            log.info("Quiz {} : commission de {} HTG au leader {} ({} joueur(s))",
                    quiz.getId(), total, leaderId, leaderLines.size());
        });
    }

    /** Partie solo perdue (pas de sans-faute), dans la transaction qui la clot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paySoloCommission(GameSession session) {
        Integer entry = session.getEntryPriceCredits();
        if (session.isTest() || session.isWon() || entry == null || entry <= 0
                || session.getQuiz().isTest()) {
            return;
        }
        Long playerId = session.getUser().getId();
        List<Line> found = new ArrayList<>();
        long rate = walletService.htgRate();
        jdbc.query(ELIGIBLE_LEADER + " AND lp.player_id = ?", rs -> {
            BigDecimal percent = rs.getBigDecimal("commission_percent");
            long amount = amount(entry, rate, percent);
            if (amount > 0) {
                found.add(new Line(rs.getLong("leader_id"), playerId, "solo:" + session.getId(),
                        entry, percent, amount));
            }
        }, playerId);
        if (found.isEmpty()) {
            return;
        }

        Line line = found.get(0);
        WalletTransaction credit = walletService.credit(line.leaderId(), BalanceType.WINNINGS,
                line.amountHtg(), WalletTransactionType.COMMISSION, "GameSession",
                String.valueOf(session.getId()), "commission:solo:" + session.getId(),
                "Commission - partie solo " + session.getId());
        insert(line, "SOLO", session.getQuiz().getId(), session.getId(), rate, credit.getId());
        log.info("Partie solo {} : commission de {} HTG au leader {}", session.getId(),
                line.amountHtg(), line.leaderId());
    }

    /** Credits x taux x pourcentage / 100, arrondi a la gourde inferieure. */
    static long amount(long credits, long rate, BigDecimal percent) {
        return BigDecimal.valueOf(credits).multiply(BigDecimal.valueOf(rate)).multiply(percent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                .longValueExact();
    }

    private void insert(Line line, String source, Long quizId, Long sessionId, long rate,
            Long transactionId) {
        jdbc.update("""
                INSERT INTO leader_commissions (leader_id, player_id, source, source_key, quiz_id,
                    session_id, base_credits, htg_rate, commission_percent, amount_htg,
                    transaction_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, line.leaderId(), line.playerId(), source, line.sourceKey(), quizId, sessionId,
                line.baseCredits(), rate, line.percent(), line.amountHtg(), transactionId,
                Timestamp.from(Instant.now()));
    }
}
