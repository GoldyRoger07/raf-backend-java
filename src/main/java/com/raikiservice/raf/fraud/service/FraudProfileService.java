package com.raikiservice.raf.fraud.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profils fraudeurs et liste des joueurs suspectes (tables {@code fraud_profiles} et
 * {@code fraud_suspects}), geres depuis le back-office.
 *
 * <p>Un joueur liste, et tout compte lie a lui par appareil (DeviceLinkService), joue
 * ses parties solo avec les valeurs durcies de son profil ({@link SoloAdjustment}) :
 * prix d'entree, secondes par question, nombre de questions. Le gain du sans-faute ne
 * change pas, l'app affiche les valeurs ajustees, et la premiere partie offerte lui est
 * refusee. Seules les parties qui demarrent ensuite sont touchees.
 */
@Service
@RequiredArgsConstructor
public class FraudProfileService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final UserRepository userRepository;
    private final DeviceLinkService deviceLinkService;
    private final AuditService auditService;

    public record Profile(Long id, String name, int priceIncreasePercent, int durationDecreasePercent,
            int questionsIncreasePercent, long suspectCount) {
    }

    public record ProfileRequest(String name, int priceIncreasePercent, int durationDecreasePercent,
            int questionsIncreasePercent) {
    }

    /** Ligne de la liste du back-office. */
    public record Suspect(Long playerId, String playerName, String email, String phone, Long profileId,
            String profileName, String note, Long addedBy, Instant addedAt, Instant updatedAt,
            int linkedAccounts) {
    }

    // --- Effet en jeu --------------------------------------------------------------

    /**
     * Durcissement a appliquer aux parties solo du joueur : celui de son profil ou d'un
     * compte lie, le plus severe valeur par valeur ; vide s'il n'est concerne par aucun.
     * Les comptes de test, qui jouent gratuitement, n'en ont jamais.
     */
    @Transactional(readOnly = true)
    public Optional<SoloAdjustment> adjustmentFor(Long userId) {
        if (userId == null || userRepository.findById(userId).map(user -> user.isTest()).orElse(true)) {
            return Optional.empty();
        }
        List<Long> group = deviceLinkService.linkedGroup(userId);
        List<SoloAdjustment> found = named.query("""
                SELECT p.price_increase_percent, p.duration_decrease_percent, p.questions_increase_percent
                FROM fraud_suspects s JOIN fraud_profiles p ON p.id = s.profile_id
                WHERE s.user_id IN (:group)
                """,
                new MapSqlParameterSource("group", group),
                (rs, i) -> new SoloAdjustment(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
        return found.stream().reduce(SoloAdjustment::harshest);
    }

    // --- Profils -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Profile> profiles() {
        return jdbc.query("""
                SELECT p.id, p.name, p.price_increase_percent, p.duration_decrease_percent,
                       p.questions_increase_percent,
                       (SELECT COUNT(*) FROM fraud_suspects s WHERE s.profile_id = p.id) AS suspects
                FROM fraud_profiles p
                ORDER BY p.name, p.id
                """,
                (rs, i) -> new Profile(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("price_increase_percent"), rs.getInt("duration_decrease_percent"),
                        rs.getInt("questions_increase_percent"), rs.getLong("suspects")));
    }

    @Transactional
    public void createProfile(Long adminId, ProfileRequest request) {
        String name = validate(request);
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbc.update("""
                    INSERT INTO fraud_profiles (name, price_increase_percent, duration_decrease_percent,
                        questions_increase_percent, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                    """, name, request.priceIncreasePercent(), request.durationDecreasePercent(),
                    request.questionsIncreasePercent(), now, now);
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        auditService.record(adminId, "FRAUD_PROFILE_CREATED", "FraudProfile", name, null, describe(request));
    }

    /** Les parties en cours gardent leurs valeurs ; les suivantes prennent les nouvelles. */
    @Transactional
    public void updateProfile(Long adminId, Long profileId, ProfileRequest request) {
        String name = validate(request);
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE fraud_profiles SET name = ?, price_increase_percent = ?, duration_decrease_percent = ?,
                        questions_increase_percent = ?, updated_at = ? WHERE id = ?
                    """, name, request.priceIncreasePercent(), request.durationDecreasePercent(),
                    request.questionsIncreasePercent(), Timestamp.from(Instant.now()), profileId);
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        if (updated == 0) {
            throw ApiException.notFound("Profil");
        }
        auditService.record(adminId, "FRAUD_PROFILE_UPDATED", "FraudProfile", String.valueOf(profileId), null,
                describe(request));
    }

    /** Refuse tant que des joueurs y sont rattaches : il faut d'abord les deplacer ou les retirer. */
    @Transactional
    public void deleteProfile(Long adminId, Long profileId) {
        Long suspects = jdbc.queryForObject("SELECT COUNT(*) FROM fraud_suspects WHERE profile_id = ?",
                Long.class, profileId);
        if (suspects != null && suspects > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Ce profil est applique a " + suspects + " joueur(s) : changez-leur de profil d'abord.");
        }
        if (jdbc.update("DELETE FROM fraud_profiles WHERE id = ?", profileId) == 0) {
            throw ApiException.notFound("Profil");
        }
        auditService.record(adminId, "FRAUD_PROFILE_DELETED", "FraudProfile", String.valueOf(profileId));
    }

    private static String validate(ProfileRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 80) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Donnez un nom au profil (80 caracteres au plus).");
        }
        check(request.priceIncreasePercent(), SoloAdjustment.MAX_PRICE_INCREASE, "La hausse du prix");
        check(request.durationDecreasePercent(), SoloAdjustment.MAX_DURATION_DECREASE, "La baisse du temps");
        check(request.questionsIncreasePercent(), SoloAdjustment.MAX_QUESTIONS_INCREASE,
                "La hausse du nombre de questions");
        return name;
    }

    private static void check(int percent, int max, String label) {
        if (percent < 0 || percent > max) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, label + " doit etre entre 0 et " + max + " %.");
        }
    }

    private static ApiException duplicateName() {
        return new ApiException(ErrorCode.CONFLICT, "Un profil porte deja ce nom.");
    }

    private static String describe(ProfileRequest request) {
        return request.name().trim() + " : prix +" + request.priceIncreasePercent() + " %, temps -"
                + request.durationDecreasePercent() + " %, questions +" + request.questionsIncreasePercent() + " %";
    }

    // --- Joueurs suspectes ---------------------------------------------------------

    /** La liste, le plus recemment ajoute d'abord. */
    @Transactional(readOnly = true)
    public List<Suspect> suspects() {
        return jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, u.email, u.phone, p.id AS profile_id, p.name AS profile,
                       s.note, s.added_by, s.created_at, s.updated_at
                FROM fraud_suspects s
                JOIN users u ON u.id = s.user_id
                JOIN fraud_profiles p ON p.id = s.profile_id
                ORDER BY s.created_at DESC, u.id
                """,
                (rs, i) -> {
                    long playerId = rs.getLong("id");
                    return new Suspect(playerId,
                            rs.getString("first_name") + " " + rs.getString("last_name"),
                            rs.getString("email"),
                            rs.getString("phone"),
                            rs.getLong("profile_id"),
                            rs.getString("profile"),
                            rs.getString("note"),
                            rs.getObject("added_by", Long.class),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant(),
                            deviceLinkService.linkedGroup(playerId).size() - 1);
                });
    }

    /** Ajoute le joueur, ou change son profil et sa note s'il est deja liste. Trace au journal d'audit. */
    @Transactional
    public void addSuspect(Long adminId, Long userId, Long profileId, String note) {
        userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Joueur"));
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Indiquez pourquoi ce joueur est suspecte.");
        }
        Boolean profileExists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM fraud_profiles WHERE id = ?)",
                Boolean.class, profileId);
        if (!Boolean.TRUE.equals(profileExists)) {
            throw ApiException.notFound("Profil");
        }
        Timestamp now = Timestamp.from(Instant.now());
        boolean present = jdbc.update("UPDATE fraud_suspects SET profile_id = ?, note = ?, updated_at = ? WHERE user_id = ?",
                profileId, reason, now, userId) > 0;
        if (!present) {
            jdbc.update("""
                    INSERT INTO fraud_suspects (user_id, profile_id, note, added_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, profileId, reason, adminId, now, now);
        }
        auditService.record(adminId, present ? "FRAUD_SUSPECT_UPDATED" : "FRAUD_SUSPECT_ADDED", "User",
                String.valueOf(userId), null, "profil " + profileId + " : " + reason);
    }

    /** Retire le joueur : ses prochaines parties solo reprennent les valeurs normales. */
    @Transactional
    public void removeSuspect(Long adminId, Long userId) {
        if (jdbc.update("DELETE FROM fraud_suspects WHERE user_id = ?", userId) > 0) {
            auditService.record(adminId, "FRAUD_SUSPECT_REMOVED", "User", String.valueOf(userId));
        }
    }
}
