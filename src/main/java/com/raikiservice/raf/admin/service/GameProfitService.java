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
 * - commissions versees aux leaders (parties perdues de leurs joueurs)
 * - credits offerts aux joueurs invites par un leader (bonus d'inscription)
 * + frais preleves sur les retraits verses
 * = resultat du jeu
 * </pre>
 *
 * <p>Les ventes sont en credits, convertis au taux du moment : le grand livre ne
 * garde pas le taux de chaque vente. Les couts hors de l'application
 * (commission des agents, frais MonCash, serveurs) n'y figurent pas : c'est une
 * marge de jeu, pas le benefice de l'entreprise.
 *
 * <p>Les credits offerts se melangent aux credits achetes dans le meme solde : on
 * ne sait pas quel ticket ils ont paye. Ils sont donc deduits comme un cout, au
 * taux du moment, des leur versement. Une fois depenses, leur vente et ce cout
 * s'annulent, et seul l'argent reellement paye par les joueurs reste au resultat.
 * Tant qu'ils ne sont pas depenses, le resultat est un peu prudent.
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

        // Quiz et comptes de test exclus (voir RealActivity).
        long salesCredits = -sum("SELECT COALESCE(SUM(t.amount), 0) " + RealActivity.TRANSACTIONS
                + "AND t.type IN ('TICKET_PURCHASE', 'SOLO_ENTRY') AND t.created_at >= ? AND t.created_at < ?", lo, hi);
        // Seuls les tickets sont rembourses sur le solde de credits ; les retraits
        // rejetes le sont sur les gains et ne sont pas une recette perdue.
        long refundsCredits = sum("SELECT COALESCE(SUM(t.amount), 0) " + RealActivity.TRANSACTIONS
                + "AND t.type = 'REFUND' AND t.balance_type = 'CREDIT' AND t.created_at >= ? AND t.created_at < ?", lo, hi);
        long prizesHtg = sum("SELECT COALESCE(SUM(t.amount), 0) " + RealActivity.TRANSACTIONS
                + "AND t.type = 'PRIZE' AND t.created_at >= ? AND t.created_at < ?", lo, hi);
        long commissionsHtg = sum("SELECT COALESCE(SUM(t.amount), 0) " + RealActivity.TRANSACTIONS
                + "AND t.type = 'COMMISSION' AND t.created_at >= ? AND t.created_at < ?", lo, hi);
        long inviteBonusesCredits = sum("SELECT COALESCE(SUM(t.amount), 0) " + RealActivity.TRANSACTIONS
                + "AND t.type = 'INVITE_BONUS' AND t.created_at >= ? AND t.created_at < ?", lo, hi);
        long feesHtg = sum("SELECT COALESCE(SUM(wr.fee_htg), 0) " + RealActivity.WITHDRAWALS
                + "AND wr.status = 'PAID' AND wr.paid_at >= ? AND wr.paid_at < ?", lo, hi);

        long ticketsSold = sum("SELECT COUNT(*) " + RealActivity.TICKETS
                + "AND tk.purchased_at >= ? AND tk.purchased_at < ? AND tk.status <> 'REFUNDED'", lo, hi);
        long soloGames = sum("SELECT COUNT(*) " + RealActivity.SESSIONS
                + "AND gs.game_mode = 'SOLO' AND gs.started_at >= ? AND gs.started_at < ?", lo, hi);

        long salesHtg = salesCredits * rate;
        long refundsHtg = refundsCredits * rate;
        long inviteBonusesHtg = inviteBonusesCredits * rate;
        long resultHtg = salesHtg - refundsHtg - prizesHtg - commissionsHtg - inviteBonusesHtg + feesHtg;

        return new GameProfitResponse(period, from, to, rate,
                salesCredits, salesHtg, refundsCredits, refundsHtg, prizesHtg, commissionsHtg,
                inviteBonusesCredits, inviteBonusesHtg, feesHtg, resultHtg,
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
