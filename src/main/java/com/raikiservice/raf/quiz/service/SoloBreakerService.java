package com.raikiservice.raf.quiz.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disjoncteur de rentabilite des quiz solo.
 *
 * <p>Un quiz solo rapporte son prix d'entree a chaque partie et coute son prix a chaque
 * sans-faute. S'il se met a perdre de l'argent (questions qui circulent, vivier trop petit,
 * fraude, prix mal regle), il faut l'arreter avant que la perte grossisse. Chaque minute
 * ({@link SoloBreakerScheduler}), pour chaque quiz solo ouvert (RUNNING), payant, ni de test
 * ni deja masque :
 * <ol>
 *   <li>on prend ses {@code window} dernieres parties terminees (SUBMITTED ou EXPIRED),
 *       comptes de test exclus, lancees depuis {@link Quiz#getBreakerCountedSince()} ;</li>
 *   <li>s'il y en a au moins {@code minGames} et que les prix verses depassent
 *       {@code maxPayoutPercent} % des recettes, le quiz est <b>masque</b> aux joueurs
 *       ({@link Quiz#isHidden()}), jamais ferme : son statut et l'argent ne changent pas, les
 *       parties en cours vont a leur terme, aucune nouvelle ne demarre ;</li>
 *   <li>les admins sont prevenus et l'action est tracee ({@code QUIZ_BREAKER_TRIPPED}).</li>
 * </ol>
 *
 * <p>Les recettes sont les credits d'entree reellement payes, convertis au taux actuel du
 * credit (comme {@link SoloQuizStatsService}) : une partie offerte compte zero recette, ce
 * qu'elle coute vraiment. Les prix sont les gourdes versees pour les sans-faute.
 *
 * <p>Un admin rearme le quiz en le demasquant ({@link QuizService#setHidden}) : le compte
 * repart de zero, seules les parties lancees ensuite comptent. Les quiz gratuits ne sont
 * jamais coupes : ils sont a perte par choix.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoloBreakerService {

    public static final int DEFAULT_WINDOW_GAMES = 50;
    public static final int DEFAULT_MIN_GAMES = 20;
    public static final int DEFAULT_MAX_PAYOUT_PERCENT = 100;
    public static final int MAX_WINDOW_GAMES = 1000;
    public static final int MAX_PAYOUT_PERCENT_LIMIT = 1000;

    private final JdbcTemplate jdbc;
    private final QuizRepository quizRepository;
    private final SettingsService settingsService;
    private final WalletService walletService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    public record Config(boolean enabled, int windowGames, int minGames, int maxPayoutPercent) {
    }

    /** Ce que le disjoncteur voit d'un quiz : sa fenetre de parties. */
    public record Measure(long games, long perfectGames, long revenueHtg, long prizesHtg) {

        /** Prix verses sur recettes, en % arrondi ; null sans recette. */
        public Long payoutPercent() {
            return revenueHtg == 0 ? null : Math.round(prizesHtg * 100.0 / revenueHtg);
        }

        boolean trips(Config config) {
            return games >= config.minGames()
                    && prizesHtg * 100 > revenueHtg * config.maxPayoutPercent();
        }
    }

    /** Quiz solo ouvert et payant, vu par le disjoncteur. */
    public record QuizRow(Long quizId, String title, long entryCredits, long prizeHtg, boolean hidden,
            Instant trippedAt, Instant countedSince, long games, long perfectGames, long revenueHtg,
            long prizesHtg, Long payoutPercent) {
    }

    public record Overview(Config config, List<QuizRow> quizzes) {
    }

    /** Resultat d'une verification : les quiz masques a cette occasion. */
    public record CheckResult(List<Long> trippedQuizIds) {
    }

    @Transactional(readOnly = true)
    public Config config() {
        boolean enabled = Boolean.parseBoolean(
                settingsService.getString(SettingKeys.APP_SOLO_BREAKER_ENABLED, "false"));
        int window = clamp(settingsService.getLong(SettingKeys.APP_SOLO_BREAKER_WINDOW_GAMES,
                DEFAULT_WINDOW_GAMES), 1, MAX_WINDOW_GAMES);
        int min = clamp(settingsService.getLong(SettingKeys.APP_SOLO_BREAKER_MIN_GAMES,
                DEFAULT_MIN_GAMES), 1, window);
        int max = clamp(settingsService.getLong(SettingKeys.APP_SOLO_BREAKER_MAX_PAYOUT_PERCENT,
                DEFAULT_MAX_PAYOUT_PERCENT), 1, MAX_PAYOUT_PERCENT_LIMIT);
        return new Config(enabled, window, min, max);
    }

    /** Enregistre les reglages ; chaque valeur changee est tracee par SettingsService. */
    @Transactional
    public void setConfig(Long adminId, Config config) {
        if (config.windowGames() < 1 || config.windowGames() > MAX_WINDOW_GAMES) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "La fenetre doit compter entre 1 et " + MAX_WINDOW_GAMES + " parties.");
        }
        if (config.minGames() < 1 || config.minGames() > config.windowGames()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Le minimum de parties doit etre entre 1 et la taille de la fenetre.");
        }
        if (config.maxPayoutPercent() < 1 || config.maxPayoutPercent() > MAX_PAYOUT_PERCENT_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "La limite doit etre entre 1 et " + MAX_PAYOUT_PERCENT_LIMIT + " %.");
        }
        Config current = config();
        if (current.windowGames() != config.windowGames()) {
            settingsService.update(adminId, SettingKeys.APP_SOLO_BREAKER_WINDOW_GAMES,
                    String.valueOf(config.windowGames()));
        }
        if (current.minGames() != config.minGames()) {
            settingsService.update(adminId, SettingKeys.APP_SOLO_BREAKER_MIN_GAMES,
                    String.valueOf(config.minGames()));
        }
        if (current.maxPayoutPercent() != config.maxPayoutPercent()) {
            settingsService.update(adminId, SettingKeys.APP_SOLO_BREAKER_MAX_PAYOUT_PERCENT,
                    String.valueOf(config.maxPayoutPercent()));
        }
        if (current.enabled() != config.enabled()) {
            settingsService.update(adminId, SettingKeys.APP_SOLO_BREAKER_ENABLED,
                    String.valueOf(config.enabled()));
        }
    }

    /** Reglages et etat de chaque quiz solo ouvert et payant, masque ou non. */
    @Transactional(readOnly = true)
    public Overview overview() {
        Config config = config();
        long rate = walletService.htgRate();
        List<QuizRow> rows = new ArrayList<>();
        for (Quiz quiz : quizRepository.findAllById(watchedQuizIds(true))) {
            Measure m = measure(quiz.getId(), config.windowGames(), rate);
            rows.add(new QuizRow(quiz.getId(), quiz.getTitle(), quiz.getTicketPriceCredits(),
                    quiz.getSoloPrizeHtg() == null ? 0 : quiz.getSoloPrizeHtg(), quiz.isHidden(),
                    quiz.getBreakerTrippedAt(), quiz.getBreakerCountedSince(), m.games(),
                    m.perfectGames(), m.revenueHtg(), m.prizesHtg(), m.payoutPercent()));
        }
        // Les plus a risque en tete : quiz coupes, puis taux de redistribution decroissant.
        rows.sort((a, b) -> {
            if ((a.trippedAt() != null) != (b.trippedAt() != null)) {
                return a.trippedAt() != null ? -1 : 1;
            }
            long pa = a.payoutPercent() == null ? (a.prizesHtg() > 0 ? Long.MAX_VALUE : -1) : a.payoutPercent();
            long pb = b.payoutPercent() == null ? (b.prizesHtg() > 0 ? Long.MAX_VALUE : -1) : b.payoutPercent();
            return Long.compare(pb, pa);
        });
        return new Overview(config, rows);
    }

    /**
     * Verifie tous les quiz surveilles et masque ceux qui perdent. Sans effet si le
     * disjoncteur est coupe. Chaque quiz dans sa propre transaction : un echec n'empeche
     * pas de verifier les autres.
     */
    public CheckResult checkAll() {
        Config config = config();
        if (!config.enabled()) {
            return new CheckResult(List.of());
        }
        List<Long> tripped = new ArrayList<>();
        for (Long quizId : watchedQuizIds(false)) {
            try {
                Boolean done = transactionTemplate.execute(status -> tripIfLosing(quizId, config));
                if (Boolean.TRUE.equals(done)) {
                    tripped.add(quizId);
                }
            } catch (RuntimeException e) {
                log.error("Disjoncteur solo : verification du quiz {} impossible", quizId, e);
            }
        }
        return new CheckResult(tripped);
    }

    /** Sous verrou du quiz : la mesure est refaite, le quiz a pu changer depuis la liste. */
    private boolean tripIfLosing(Long quizId, Config config) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId).orElse(null);
        if (quiz == null || !watched(quiz) || quiz.isHidden()) {
            return false;
        }
        Measure m = measure(quizId, config.windowGames(), walletService.htgRate());
        if (!m.trips(config)) {
            return false;
        }
        Instant now = Instant.now();
        quiz.setHidden(true);
        quiz.setBreakerTrippedAt(now);
        String detail = m.games() + " parties, " + m.perfectGames() + " sans-faute, "
                + m.prizesHtg() + " HTG verses / " + m.revenueHtg() + " HTG de recettes (limite "
                + config.maxPayoutPercent() + " %)";
        auditService.record(null, "QUIZ_BREAKER_TRIPPED", "Quiz", String.valueOf(quizId), null, detail);
        notificationService.notifyAdmins(NotificationMessages.soloBreakerTripped(quiz, m.games(),
                m.perfectGames(), m.revenueHtg(), m.prizesHtg(),
                m.payoutPercent() == null ? 0 : m.payoutPercent(), config.maxPayoutPercent()));
        log.warn("Disjoncteur solo : quiz {} masque ({})", quizId, detail);
        return true;
    }

    /** Fenetre des {@code window} dernieres parties terminees du quiz. */
    Measure measure(Long quizId, int window, long rate) {
        return jdbc.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(w.won), 0), COALESCE(SUM(w.entry), 0), COALESCE(SUM(w.prize), 0)
                FROM (SELECT CASE WHEN gs.won THEN 1 ELSE 0 END AS won,
                             COALESCE(gs.entry_price_credits, 0) AS entry,
                             CASE WHEN gs.won THEN COALESCE(gs.prize_htg, 0) ELSE 0 END AS prize
                      FROM game_sessions gs
                      JOIN users u ON u.id = gs.user_id
                      JOIN quizzes q ON q.id = gs.quiz_id
                      WHERE gs.quiz_id = ? AND gs.game_mode = 'SOLO' AND u.is_test = FALSE
                        AND gs.status IN ('SUBMITTED', 'EXPIRED')
                        AND (q.breaker_counted_since IS NULL OR gs.started_at >= q.breaker_counted_since)
                      ORDER BY gs.started_at DESC, gs.id DESC
                      LIMIT ?) w
                """,
                (rs, row) -> new Measure(rs.getLong(1), rs.getLong(2), rs.getLong(3) * rate, rs.getLong(4)),
                quizId, window);
    }

    /** Quiz solo ouverts, payants, hors test ; {@code includeHidden} : masques compris. */
    private List<Long> watchedQuizIds(boolean includeHidden) {
        return jdbc.queryForList("SELECT id FROM quizzes WHERE game_mode = 'SOLO' AND status = ? "
                + "AND is_test = FALSE AND ticket_price_credits > 0"
                + (includeHidden ? "" : " AND hidden = FALSE") + " ORDER BY id",
                Long.class, QuizStatus.RUNNING.name());
    }

    private static boolean watched(Quiz quiz) {
        return quiz.isSolo() && quiz.getStatus() == QuizStatus.RUNNING && !quiz.isTest() && !quiz.isFree();
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
