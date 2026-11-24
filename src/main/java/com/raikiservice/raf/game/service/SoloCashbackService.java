package com.raikiservice.raf.game.service;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.fraud.service.AntiFraudService;
import com.raikiservice.raf.fraud.service.FraudProfileService;
import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.repository.WalletRepository;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cashback du quasi sans-faute en solo.
 *
 * <p>Une partie solo <b>payante</b> terminee avec <b>exactement une erreur</b> (9/10, 14/15,
 * 19/20...) rend au joueur une part de son prix d'entree, en credits (solde CREDIT, non
 * retirable), transaction {@link WalletTransactionType#SOLO_CASHBACK}. Arrondi a l'unite
 * inferieure, au moins 1 credit.
 *
 * <p>Pas de cashback :
 * <ul>
 *   <li>interrupteur general coupe (par defaut), ou quiz a 0 % ;</li>
 *   <li>partie offerte (prix d'entree 0 : quiz gratuit, premiere partie offerte), compte de test ;</li>
 *   <li>partie de moins de {@code minQuestions} questions ;</li>
 *   <li>partie ou l'anti-fraude a compte des reponses fausses : sans elle, c'etait un sans-faute ;</li>
 *   <li>joueur sur la liste anti-fraude ou sous profil fraudeur (lui ou un compte lie) ;</li>
 *   <li>plafond du jour atteint ({@code dailyLimit} cashbacks par joueur et par jour de jeu).</li>
 * </ul>
 *
 * <p>Les credits rendus diminuent les recettes du quiz pour le disjoncteur et les statistiques.
 * Le leader du joueur touche toujours sa commission : la partie reste perdue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoloCashbackService {

    public static final int DEFAULT_PERCENT = 50;
    public static final int DEFAULT_DAILY_LIMIT = 3;
    public static final int DEFAULT_MIN_QUESTIONS = 5;
    public static final int MAX_DAILY_LIMIT = 100;
    public static final int MAX_MIN_QUESTIONS = 200;

    private final SettingsService settingsService;
    private final WalletService walletService;
    private final WalletRepository walletRepository;
    private final AntiFraudService antiFraudService;
    private final FraudProfileService fraudProfileService;
    private final JdbcTemplate jdbc;

    public record Config(boolean enabled, int percent, int dailyLimit, int minQuestions) {
    }

    @Transactional(readOnly = true)
    public Config config() {
        boolean enabled = Boolean.parseBoolean(
                settingsService.getString(SettingKeys.APP_SOLO_CASHBACK_ENABLED, "false"));
        int percent = clamp(settingsService.getLong(SettingKeys.APP_SOLO_CASHBACK_PERCENT, DEFAULT_PERCENT), 1, 100);
        int limit = clamp(settingsService.getLong(SettingKeys.APP_SOLO_CASHBACK_DAILY_LIMIT, DEFAULT_DAILY_LIMIT),
                1, MAX_DAILY_LIMIT);
        int min = clamp(settingsService.getLong(SettingKeys.APP_SOLO_CASHBACK_MIN_QUESTIONS, DEFAULT_MIN_QUESTIONS),
                2, MAX_MIN_QUESTIONS);
        return new Config(enabled, percent, limit, min);
    }

    /** Enregistre les reglages ; chaque valeur changee est tracee par SettingsService. */
    @Transactional
    public Config setConfig(Long adminId, Config config) {
        if (config.percent() < 1 || config.percent() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Le taux doit etre entre 1 et 100 %.");
        }
        if (config.dailyLimit() < 1 || config.dailyLimit() > MAX_DAILY_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Le plafond doit etre entre 1 et " + MAX_DAILY_LIMIT + " par jour.");
        }
        if (config.minQuestions() < 2 || config.minQuestions() > MAX_MIN_QUESTIONS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Le minimum doit etre entre 2 et " + MAX_MIN_QUESTIONS + " questions.");
        }
        Config current = config();
        change(adminId, SettingKeys.APP_SOLO_CASHBACK_PERCENT, current.percent(), config.percent());
        change(adminId, SettingKeys.APP_SOLO_CASHBACK_DAILY_LIMIT, current.dailyLimit(), config.dailyLimit());
        change(adminId, SettingKeys.APP_SOLO_CASHBACK_MIN_QUESTIONS, current.minQuestions(), config.minQuestions());
        if (current.enabled() != config.enabled()) {
            settingsService.update(adminId, SettingKeys.APP_SOLO_CASHBACK_ENABLED, String.valueOf(config.enabled()));
        }
        return config();
    }

    /**
     * Taux applique a ce quiz, affiche au joueur ; null s'il n'y a pas de cashback (interrupteur
     * coupe, quiz gratuit ou de groupe, quiz a 0 %, parties trop courtes).
     */
    @Transactional(readOnly = true)
    public Integer effectivePercent(Quiz quiz) {
        return effectivePercent(quiz, config());
    }

    static Integer effectivePercent(Quiz quiz, Config config) {
        if (!config.enabled() || !quiz.isSolo() || quiz.isFree()
                || quiz.getQuestionsPerGame() < config.minQuestions()) {
            return null;
        }
        Integer own = quiz.getSoloCashbackPercent();
        int percent = own == null ? config.percent() : own;
        return percent <= 0 ? null : Math.min(percent, 100);
    }

    /** Credits rendus pour un prix d'entree : arrondi inferieur, au moins 1. */
    static int amount(int entryPriceCredits, int percent) {
        return Math.max(1, entryPriceCredits * percent / 100);
    }

    /**
     * Fin d'une partie solo non gagnante : verse le cashback si elle y a droit. Dans la
     * transaction de la fin de partie ; cle d'idempotence par partie, un rejeu ne paie
     * jamais deux fois.
     *
     * @param antiFraudVoided l'anti-fraude a compte des bonnes reponses fausses
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payIfNearPerfect(GameSession session, int totalQuestions, int correctCount,
            boolean antiFraudVoided) {
        if (!session.isSolo() || session.isTest() || antiFraudVoided || session.getCashbackCredits() != null) {
            return;
        }
        Integer price = session.getEntryPriceCredits();
        if (price == null || price <= 0 || totalQuestions < 2 || correctCount != totalQuestions - 1) {
            return;
        }
        Config config = config();
        if (totalQuestions < config.minQuestions()) {
            return;
        }
        Quiz quiz = session.getQuiz();
        Integer own = quiz.getSoloCashbackPercent();
        int percent = own == null ? config.percent() : Math.min(own, 100);
        if (!config.enabled() || percent <= 0) {
            return;
        }
        Long userId = session.getUser().getId();
        if (antiFraudService.isListed(session.getUser()) || fraudProfileService.adjustmentFor(userId).isPresent()) {
            log.info("Partie solo {} : quasi sans-faute sans cashback (joueur {} vise par l'anti-fraude)",
                    session.getId(), userId);
            return;
        }

        // Verrou du portefeuille avant de compter : deux fins de partie simultanees du meme
        // joueur ne passent pas toutes deux sous le plafond.
        walletRepository.findByUserIdForUpdate(userId);
        Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM game_sessions WHERE user_id = ? "
                + "AND solo_day = ? AND cashback_credits IS NOT NULL AND id <> ?",
                Integer.class, userId, session.getSoloDay(), session.getId());
        if (already != null && already >= config.dailyLimit()) {
            log.info("Partie solo {} : quasi sans-faute, plafond du jour atteint ({}) pour le joueur {}",
                    session.getId(), config.dailyLimit(), userId);
            return;
        }

        int credits = amount(price, percent);
        WalletTransaction entry = walletService.credit(
                userId,
                BalanceType.CREDIT,
                credits,
                WalletTransactionType.SOLO_CASHBACK,
                "GameSession",
                String.valueOf(session.getId()),
                "solo-cashback:session:" + session.getId(),
                "Cashback quasi sans-faute (partie " + session.getId() + ")");
        session.setCashbackCredits(credits);
        session.setCashbackTransactionId(entry.getId());
        log.info("Partie solo {} : {}/{}, {} credit(s) rendus ({} %)", session.getId(), correctCount,
                totalQuestions, credits, percent);
    }

    private void change(Long adminId, String key, int current, int requested) {
        if (current != requested) {
            settingsService.update(adminId, key, String.valueOf(requested));
        }
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
