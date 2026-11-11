package com.raikiservice.raf.game.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.settings.PlayerAppService;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Premiere partie solo offerte, une seule fois par compte, si l'interrupteur de l'ecran
 * App joueur est active (PlayerAppService#firstSoloFreeEnabled, coupe par defaut).
 *
 * <p>Y a droit un compte qui n'a jamais paye de partie solo (les quiz deja gratuits ne
 * comptent pas) et n'a pas deja recu l'offre, et dont aucun compte lie par appareil
 * (DeviceLinkService) ne l'a recue ni n'a paye de partie solo. Les comptes de test jouent
 * deja gratuitement et n'en ont pas. Le sans-faute rapporte le prix complet.
 */
@Service
@RequiredArgsConstructor
public class FirstSoloOfferService {

    private final PlayerAppService playerAppService;
    private final DeviceLinkService deviceLinkService;
    private final UserRepository userRepository;
    private final NamedParameterJdbcTemplate named;

    /** Pour l'ecran de jeu de l'app : la prochaine partie solo payante serait-elle offerte ? */
    @Transactional(readOnly = true)
    public boolean offeredTo(Long userId) {
        return eligible(userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur")));
    }

    @Transactional(readOnly = true)
    public boolean eligible(User user) {
        if (!playerAppService.firstSoloFreeEnabled() || user.isTest()) {
            return false;
        }
        List<Long> group = deviceLinkService.linkedGroup(user.getId());
        Boolean used = named.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM solo_free_offers WHERE user_id IN (:group))"
                        + " OR EXISTS (SELECT 1 FROM game_sessions WHERE user_id IN (:group)"
                        + "   AND game_mode = 'SOLO' AND is_test = FALSE AND entry_price_credits > 0)",
                new MapSqlParameterSource("group", group), Boolean.class);
        return !Boolean.TRUE.equals(used);
    }

    /**
     * Reserve l'offre pour la partie qui demarre. La cle primaire departage deux lancements
     * simultanes : le second trouve la ligne et paie normalement.
     *
     * @return vrai si cette partie est offerte
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(User user) {
        if (!eligible(user)) {
            return false;
        }
        return named.update("INSERT IGNORE INTO solo_free_offers (user_id, created_at) VALUES (:user, :at)",
                new MapSqlParameterSource("user", user.getId()).addValue("at", Timestamp.from(Instant.now()))) == 1;
    }

    /** Rattache l'offre reservee a la partie, une fois celle-ci enregistree. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(Long userId, Long sessionId) {
        named.update("UPDATE solo_free_offers SET session_id = :session WHERE user_id = :user",
                new MapSqlParameterSource("session", sessionId).addValue("user", userId));
    }
}
