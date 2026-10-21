package com.raikiservice.raf.admin.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import com.raikiservice.raf.admin.web.dto.GameProfitResponse;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resultat du jeu sur une periode : ce que les quiz ont rapporte a RAF.
 *
 * <pre>
 *   ventes de jeu (tickets + parties solo)
 * - remboursements (quiz annules)
 * - gains verses aux joueurs (classements + sans-faute)
 * + frais preleves sur les retraits verses
 * = resultat du jeu
 * </pre>
 *
 * <p>Les ventes sont en credits, convertis au taux du moment : le grand livre ne
 * garde pas le taux de chaque vente. Les couts hors de l'application
 * (commission des agents, frais MonCash, serveurs) n'y figurent pas : c'est une
 * marge de jeu, pas le benefice de l'entreprise.
 */
@Service
@RequiredArgsConstructor
public class GameProfitService {

    /** Les mois et les jours suivent le calendrier d'Haiti, comme le reste du jeu. */
    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");

    private final JdbcTemplate jdbc;
    private final WalletService walletService;

    public enum Period {
        LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH, ALL_TIME
    }

    @Transactional(readOnly = true)
    public GameProfitResponse profit(Period period) {
        Instant now = Instant.now();
        Instant from = start(period, now);
        Instant to = period == Period.LAST_MONTH ? monthStart(now, 0) : now;
        long rate = walletService.htgRate();

        // Sans debut (depuis le lancement) : une borne basse qui couvre tout.
        Timestamp lo = Timestamp.from(from == null ? Instant.EPOCH : from);
        Timestamp hi = Timestamp.from(to);

        long salesCredits = -sum("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions "
                + "WHERE type IN ('TICKET_PURCHASE', 'SOLO_ENTRY') AND created_at >= ? AND created_at < ?", lo, hi);
        // Seuls les tickets sont rembourses sur le solde de credits ; les retraits
        // rejetes le sont sur les gains et ne sont pas une recette perdue.
        long refundsCredits = sum("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions "
                + "WHERE type = 'REFUND' AND balance_type = 'CREDIT' AND created_at >= ? AND created_at < ?", lo, hi);
        long prizesHtg = sum("SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions "
                + "WHERE type = 'PRIZE' AND created_at >= ? AND created_at < ?", lo, hi);
        long feesHtg = sum("SELECT COALESCE(SUM(fee_htg), 0) FROM withdrawal_requests "
                + "WHERE status = 'PAID' AND paid_at >= ? AND paid_at < ?", lo, hi);

        long ticketsSold = sum("SELECT COUNT(*) FROM tickets "
                + "WHERE purchased_at >= ? AND purchased_at < ? AND status <> 'REFUNDED'", lo, hi);
        long soloGames = sum("SELECT COUNT(*) FROM game_sessions "
                + "WHERE game_mode = 'SOLO' AND started_at >= ? AND started_at < ?", lo, hi);

        long salesHtg = salesCredits * rate;
        long refundsHtg = refundsCredits * rate;
        long resultHtg = salesHtg - refundsHtg - prizesHtg + feesHtg;

        return new GameProfitResponse(period, from, to, rate,
                salesCredits, salesHtg, refundsCredits, refundsHtg, prizesHtg, feesHtg, resultHtg,
                ticketsSold, soloGames);
    }

    /** Debut de la periode, ou null pour « depuis le lancement ». */
    private static Instant start(Period period, Instant now) {
        return switch (period) {
            case LAST_7_DAYS -> now.minus(7, ChronoUnit.DAYS);
            case LAST_30_DAYS -> now.minus(30, ChronoUnit.DAYS);
            case THIS_MONTH -> monthStart(now, 0);
            case LAST_MONTH -> monthStart(now, 1);
            case ALL_TIME -> null;
        };
    }

    /** Premier jour du mois, minuit a Haiti, {@code monthsBack} mois en arriere. */
    private static Instant monthStart(Instant now, int monthsBack) {
        return LocalDate.ofInstant(now, HAITI).withDayOfMonth(1).minusMonths(monthsBack)
                .atStartOfDay(HAITI).toInstant();
    }

    private long sum(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
