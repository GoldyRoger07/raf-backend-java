package com.raikiservice.raf.auth.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appareils vus par compte (table {@code user_devices}) et comptes lies par un
 * appareil commun.
 *
 * <p>L'identifiant d'appareil est declare par le client : sur le web, vider les
 * donnees du site en donne un nouveau. Mais un compte garde tous les appareils sur
 * lesquels il a ete vu ; pour qu'un second compte echappe aux liens, il faut effacer
 * le navigateur avant chaque connexion et ne jamais rouvrir l'ancien compte au meme
 * endroit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLinkService {

    /** Une meme paire (compte, appareil) n'est reecrite qu'une fois par heure. */
    private static final long SEEN_REFRESH_MS = 60 * 60 * 1000L;
    private static final int SEEN_CACHE_MAX = 100_000;

    /**
     * Profondeur du groupe pour la limite solo : les comptes vus sur un appareil du
     * joueur (1), et ceux vus sur un appareil de ces derniers (2). Assez pour suivre
     * un joueur qui a vide son navigateur une fois entre deux comptes, sans englober
     * tout un cybercafe.
     */
    static final int SOLO_LINK_DEPTH = 2;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private final Map<String, Long> recentlySeen = new ConcurrentHashMap<>();

    /** Compte lie, pour la fiche joueur du back-office. */
    public record LinkedAccount(Long playerId, String playerName, String email, String phone,
            boolean test, int sharedDevices, Instant lastSeenAt) {
    }

    /**
     * Note l'appareil d'une requete authentifiee. Peu couteux : au plus une ecriture
     * par heure et par paire (compte, appareil) et par instance. Ne leve jamais.
     */
    public void seen(Long userId, String deviceId) {
        if (userId == null || deviceId == null) {
            return;
        }
        String key = userId + "|" + deviceId;
        long now = System.currentTimeMillis();
        Long last = recentlySeen.get(key);
        if (last != null && now - last < SEEN_REFRESH_MS) {
            return;
        }
        try {
            record(userId, deviceId, Instant.ofEpochMilli(now));
            if (recentlySeen.size() >= SEEN_CACHE_MAX) {
                recentlySeen.clear();
            }
            recentlySeen.put(key, now);
        } catch (RuntimeException e) {
            // Un compte supprime entre-temps, une base indisponible : la requete passe.
            log.warn("Appareil non enregistre pour le joueur {} : {}", userId, e.getMessage());
        }
    }

    /** Ecrit la paire (compte, appareil), dans la transaction courante s'il y en a une. */
    @Transactional
    public void record(Long userId, String deviceId, Instant at) {
        if (userId == null || deviceId == null) {
            return;
        }
        Timestamp when = Timestamp.from(at);
        jdbc.update("INSERT INTO user_devices (user_id, device_id, first_seen_at, last_seen_at) "
                + "VALUES (?, ?, ?, ?) AS new "
                + "ON DUPLICATE KEY UPDATE last_seen_at = GREATEST(user_devices.last_seen_at, new.last_seen_at)",
                userId, deviceId, when, when);
    }

    /**
     * Un autre compte du groupe du joueur (lies par appareil, jusqu'a
     * {@link #SOLO_LINK_DEPTH} niveaux) a-t-il deja joue ce sujet en solo ce jour-la ?
     * Les parties de test n'ont pas de jour solo et ne comptent pas.
     */
    @Transactional(readOnly = true)
    public boolean linkedAccountPlayedSolo(Long userId, Long categoryId, LocalDate day) {
        Boolean found = jdbc.queryForObject(
                "WITH RECURSIVE linked (user_id, depth) AS ("
                        + " SELECT CAST(? AS SIGNED), 0"
                        + " UNION"
                        + " SELECT other.user_id, l.depth + 1 FROM linked l"
                        + " JOIN user_devices mine ON mine.user_id = l.user_id"
                        + " JOIN user_devices other ON other.device_id = mine.device_id"
                        + " WHERE l.depth < ?"
                        + ") SELECT EXISTS (SELECT 1 FROM game_sessions s"
                        + " WHERE s.user_id IN (SELECT user_id FROM linked) AND s.user_id <> ?"
                        + " AND s.category_id = ? AND s.solo_day = ?)",
                Boolean.class, userId, SOLO_LINK_DEPTH, userId, categoryId, java.sql.Date.valueOf(day));
        return Boolean.TRUE.equals(found);
    }

    /** Comptes vus sur au moins un appareil du joueur, le plus recemment vu d'abord. */
    @Transactional(readOnly = true)
    public List<LinkedAccount> linkedAccounts(Long userId) {
        return jdbc.query(
                "SELECT u.id, u.first_name, u.last_name, u.email, u.phone, u.is_test,"
                        + " COUNT(DISTINCT other.device_id) AS shared, MAX(other.last_seen_at) AS last_seen"
                        + " FROM user_devices mine"
                        + " JOIN user_devices other ON other.device_id = mine.device_id AND other.user_id <> mine.user_id"
                        + " JOIN users u ON u.id = other.user_id"
                        + " WHERE mine.user_id = ?"
                        + " GROUP BY u.id, u.first_name, u.last_name, u.email, u.phone, u.is_test"
                        + " ORDER BY last_seen DESC, u.id",
                (rs, i) -> new LinkedAccount(
                        rs.getLong("id"),
                        rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getBoolean("is_test"),
                        rs.getInt("shared"),
                        rs.getTimestamp("last_seen").toInstant()),
                userId);
    }

    /** Nombre de comptes lies directement, par joueur : pour la file des retraits. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> linkedCounts(Collection<Long> userIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (userIds.isEmpty()) {
            return counts;
        }
        named.query(
                "SELECT mine.user_id, COUNT(DISTINCT other.user_id) AS n FROM user_devices mine"
                        + " JOIN user_devices other ON other.device_id = mine.device_id AND other.user_id <> mine.user_id"
                        + " WHERE mine.user_id IN (:ids) GROUP BY mine.user_id",
                new MapSqlParameterSource("ids", userIds),
                rs -> {
                    counts.put(rs.getLong(1), rs.getInt(2));
                });
        return counts;
    }
}
