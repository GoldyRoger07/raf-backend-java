package com.raikiservice.raf.fraud.service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.quiz.domain.Quiz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anti-fraude : un joueur vise ne peut pas faire de sans-faute en solo.
 *
 * <p>Vise ({@link #appliesTo(User, Quiz)}), les trois reglages combines par OU :
 * l'interrupteur « pour tous » (coupe par defaut), OU un joueur de la liste
 * {@code anti_fraud_targets} (ce compte seul, pas ses comptes lies par appareil), OU la
 * case anti-fraude du quiz solo (decochee par defaut), qui vise tous les joueurs de ce
 * quiz. Aucun reglage ne retire l'anti-fraude a un joueur vise par un autre. Le staff
 * (moderateur, admin, super admin) n'est jamais vise ; les leaders et les comptes de test
 * le sont, ces derniers pour verifier le parcours.
 *
 * <p>L'etat est lu au lancement d'une partie solo et garde dans la partie : l'activer
 * ou le couper ne touche que les parties qui demarrent ensuite. A la correction, une
 * partie visee entierement juste voit 1 a 3 de ses bonnes reponses (nombre et questions
 * tires au hasard) comptees fausses ({@link #pickVoided}). Une partie avec deja une
 * erreur n'est pas touchee. Le groupe ne change pas.
 *
 * <p>Ajout automatique (coupe par defaut) : apres N sans-faute solo payants (prix d'entree
 * non nul : ni offerte, ni quiz gratuit, ni essai), le joueur entre dans la liste, note
 * automatique, sans auteur. Ce compte seul, depuis toujours ; jamais le staff ni les
 * comptes de test. Le Nieme sans-faute est paye, l'anti-fraude touche les parties lancees
 * ensuite. Un joueur retire de la liste repart de zero ({@code anti_fraud_auto_resets}).
 * A l'activation, ou quand le reglage change, ceux qui ont deja N sans-faute sont ajoutes.
 */
@Service
@RequiredArgsConstructor
public class AntiFraudService {

    /** Au plus ce nombre de bonnes reponses comptees fausses. */
    public static final int MAX_VOIDED = 3;

    /** N par defaut, et la borne haute acceptee depuis l'ecran. */
    public static final int DEFAULT_AUTO_THRESHOLD = 3;
    public static final int MAX_AUTO_THRESHOLD = 1000;

    /**
     * Sans-faute solo payants qui comptent pour l'ajout automatique : depuis le dernier
     * retrait du joueur de la liste, s'il y en a eu un.
     */
    private static final String COUNTED_PERFECTS = """
            FROM game_sessions g
            LEFT JOIN anti_fraud_auto_resets r ON r.user_id = g.user_id
            WHERE g.game_mode = 'SOLO' AND g.perfect = TRUE AND g.is_test = FALSE
              AND g.entry_price_credits > 0
              AND (r.counted_since IS NULL OR g.submitted_at > r.counted_since)
            """;

    private final Random random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final AuditService auditService;

    /** Ligne de la liste du back-office. */
    public record Target(Long playerId, String playerName, String email, String phone, boolean test,
            String note, Long addedBy, boolean auto, Instant addedAt, Instant updatedAt) {
    }

    /** Ecran du back-office : interrupteur global, ajout automatique et liste. */
    public record Overview(boolean allPlayers, boolean autoEnabled, int autoThreshold, List<Target> targets) {
    }

    // --- Effet en jeu --------------------------------------------------------------

    /**
     * L'anti-fraude vise-t-il ce joueur sur ce quiz ? Lu au lancement d'une partie solo.
     * Interrupteur « pour tous » OU liste OU case du quiz, jamais le staff.
     */
    @Transactional(readOnly = true)
    public boolean appliesTo(User user, Quiz quiz) {
        if (user == null || isStaff(user)) {
            return false;
        }
        return (quiz != null && quiz.isAntiFraud()) || appliesTo(user);
    }

    /** Le joueur est-il vise hors case du quiz (interrupteur « pour tous » OU liste) ? */
    @Transactional(readOnly = true)
    public boolean appliesTo(User user) {
        if (user == null || isStaff(user)) {
            return false;
        }
        if (allPlayers()) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM anti_fraud_targets WHERE user_id = ?)", Boolean.class, user.getId()));
    }

    /**
     * Bonnes reponses a compter fausses : entre 1 et {@link #MAX_VOIDED} (sans depasser
     * leur nombre), nombre et reponses tires au hasard.
     */
    public <T> List<T> pickVoided(List<T> correctAnswers) {
        if (correctAnswers.isEmpty()) {
            return List.of();
        }
        int count = 1 + random.nextInt(Math.min(MAX_VOIDED, correctAnswers.size()));
        List<T> shuffled = new ArrayList<>(correctAnswers);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, count));
    }

    private static boolean isStaff(User user) {
        return user.getRoles().stream().anyMatch(role -> role.getName().isStaff());
    }

    // --- Back-office ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public boolean allPlayers() {
        return Boolean.parseBoolean(settingsService.getString(SettingKeys.APP_ANTI_FRAUD_ALL_PLAYERS, "false"));
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(allPlayers(), autoEnabled(), autoThreshold(), targets());
    }

    // --- Ajout automatique ---------------------------------------------------------

    @Transactional(readOnly = true)
    public boolean autoEnabled() {
        return Boolean.parseBoolean(settingsService.getString(SettingKeys.APP_ANTI_FRAUD_AUTO_ENABLED, "false"));
    }

    @Transactional(readOnly = true)
    public int autoThreshold() {
        long value = settingsService.getLong(SettingKeys.APP_ANTI_FRAUD_AUTO_THRESHOLD, DEFAULT_AUTO_THRESHOLD);
        return (int) Math.max(1, Math.min(MAX_AUTO_THRESHOLD, value));
    }

    /**
     * Regle l'ajout automatique. Active : les joueurs qui ont deja N sans-faute payants
     * (depuis leur dernier retrait) sont ajoutes tout de suite. Trace au journal d'audit.
     *
     * @return le nombre de joueurs ajoutes d'un coup
     */
    @Transactional
    public int setAuto(Long adminId, boolean enabled, int threshold) {
        if (threshold < 1 || threshold > MAX_AUTO_THRESHOLD) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Le nombre de sans-faute doit etre entre 1 et " + MAX_AUTO_THRESHOLD + ".");
        }
        if (threshold != autoThreshold()) {
            settingsService.update(adminId, SettingKeys.APP_ANTI_FRAUD_AUTO_THRESHOLD, String.valueOf(threshold));
        }
        if (enabled != autoEnabled()) {
            settingsService.update(adminId, SettingKeys.APP_ANTI_FRAUD_AUTO_ENABLED, String.valueOf(enabled));
        }
        if (!enabled) {
            return 0;
        }
        List<Long> reached = jdbc.queryForList("SELECT g.user_id " + COUNTED_PERFECTS + """
                  AND NOT EXISTS (SELECT 1 FROM anti_fraud_targets t WHERE t.user_id = g.user_id)
                GROUP BY g.user_id
                HAVING COUNT(*) >= ?
                """, Long.class, threshold);
        int added = 0;
        for (Long userId : reached) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && autoAddable(user)) {
                insertAuto(userId, threshold);
                added++;
            }
        }
        return added;
    }

    /**
     * Apres un sans-faute solo paye : ajoute le joueur a la liste s'il atteint N. Cette
     * partie n'est peut-etre pas encore ecrite en base : elle est comptee a part.
     */
    @Transactional
    public void afterPaidPerfect(User user, Long sessionId) {
        if (user == null || !autoEnabled() || !autoAddable(user)) {
            return;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM anti_fraud_targets WHERE user_id = ?)", Boolean.class, user.getId()))) {
            return;
        }
        int threshold = autoThreshold();
        Long earlier = jdbc.queryForObject("SELECT COUNT(*) " + COUNTED_PERFECTS + " AND g.user_id = ? AND g.id <> ?",
                Long.class, user.getId(), sessionId);
        if ((earlier == null ? 0 : earlier) + 1 >= threshold) {
            insertAuto(user.getId(), threshold);
        }
    }

    /** Joueurs et leaders ; jamais le staff ni les comptes de test. */
    private static boolean autoAddable(User user) {
        return !isStaff(user) && !user.isTest();
    }

    private void insertAuto(Long userId, int threshold) {
        Timestamp now = Timestamp.from(Instant.now());
        String note = "Ajout automatique : " + threshold + " sans-faute solo payant" + (threshold > 1 ? "s" : "");
        jdbc.update("""
                INSERT INTO anti_fraud_targets (user_id, note, added_by, auto, created_at, updated_at)
                VALUES (?, ?, NULL, TRUE, ?, ?)
                """, userId, note, now, now);
        auditService.record(null, "ANTI_FRAUD_TARGET_AUTO_ADDED", "User", String.valueOf(userId), null, note);
    }

    /** Trace au journal d'audit (SETTING_UPDATED). Parties suivantes seulement. */
    @Transactional
    public void setAllPlayers(Long adminId, boolean enabled) {
        if (enabled != allPlayers()) {
            settingsService.update(adminId, SettingKeys.APP_ANTI_FRAUD_ALL_PLAYERS, String.valueOf(enabled));
        }
    }

    /** La liste, le plus recemment ajoute d'abord. */
    @Transactional(readOnly = true)
    public List<Target> targets() {
        return jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, u.email, u.phone, u.is_test,
                       t.note, t.added_by, t.auto, t.created_at, t.updated_at
                FROM anti_fraud_targets t
                JOIN users u ON u.id = t.user_id
                ORDER BY t.created_at DESC, u.id
                """,
                (rs, i) -> new Target(rs.getLong("id"),
                        rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getBoolean("is_test"),
                        rs.getString("note"),
                        rs.getObject("added_by", Long.class),
                        rs.getBoolean("auto"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    /** Ajoute le joueur, ou met a jour sa note s'il est deja liste. Trace au journal d'audit. */
    @Transactional
    public void addTarget(Long adminId, Long userId, String note) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Joueur"));
        if (isStaff(user)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Ce compte fait partie du staff : l'anti-fraude ne s'applique jamais au staff.");
        }
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty() || reason.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Indiquez pourquoi ce joueur est vise (255 caracteres au plus).");
        }
        Timestamp now = Timestamp.from(Instant.now());
        // Une note modifiee a la main fait de l'ajout automatique un ajout manuel.
        boolean present = jdbc.update(
                "UPDATE anti_fraud_targets SET note = ?, auto = FALSE, updated_at = ? WHERE user_id = ?",
                reason, now, userId) > 0;
        if (!present) {
            jdbc.update("""
                    INSERT INTO anti_fraud_targets (user_id, note, added_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, userId, reason, adminId, now, now);
        }
        auditService.record(adminId, present ? "ANTI_FRAUD_TARGET_UPDATED" : "ANTI_FRAUD_TARGET_ADDED", "User",
                String.valueOf(userId), null, reason);
    }

    /**
     * Retire le joueur : ses prochaines parties solo sont corrigees normalement, et son
     * compteur d'ajout automatique repart de zero.
     */
    @Transactional
    public void removeTarget(Long adminId, Long userId) {
        if (jdbc.update("DELETE FROM anti_fraud_targets WHERE user_id = ?", userId) > 0) {
            Timestamp now = Timestamp.from(Instant.now());
            if (jdbc.update("UPDATE anti_fraud_auto_resets SET counted_since = ? WHERE user_id = ?", now, userId) == 0) {
                jdbc.update("INSERT INTO anti_fraud_auto_resets (user_id, counted_since) VALUES (?, ?)", userId, now);
            }
            auditService.record(adminId, "ANTI_FRAUD_TARGET_REMOVED", "User", String.valueOf(userId));
        }
    }
}
