package com.raikiservice.raf.game.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.fraud.service.FraudProfileService;

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
 * deja gratuitement et n'en ont pas, ni les joueurs suspectes de fraude (FraudProfileService,
 * comptes lies compris). Le sans-faute rapporte le prix complet.
 */
@Service
@RequiredArgsConstructor
public class FirstSoloOfferService {

    private final PlayerAppService playerAppService;
    private final DeviceLinkService deviceLinkService;
    private final UserRepository userRepository;
    private final NamedParameterJdbcTemplate named;
    private final FraudProfileService fraudProfileService;

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
        if (fraudProfileService.adjustmentFor(user.getId()).isPresent()) {
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

    /** Raison du droit (ou non) a la premiere partie offerte, pour la fiche joueur du back-office. */
    public enum OfferReason {
        /** Y a droit : sa prochaine partie solo payante sera offerte. */
        ELIGIBLE,
        /** A deja recu son offre ({@code sessionId} : la partie offerte). */
        RECEIVED,
        /** Interrupteur de l'ecran App joueur coupe : personne n'y a droit. */
        DISABLED,
        /** Compte de test : joue deja gratuitement. */
        TEST,
        /** Lui ou un compte lie est liste comme fraudeur. */
        FRAUD,
        /** A deja paye une partie solo. */
        PAID,
        /** Un compte lie par appareil a deja recu l'offre ({@code linkedPlayer*}). */
        LINKED_RECEIVED,
        /** Un compte lie par appareil a deja paye une partie solo ({@code linkedPlayer*}). */
        LINKED_PAID
    }

    public record OfferStatus(boolean eligible, OfferReason reason, Long sessionId,
            Long linkedPlayerId, String linkedPlayerName) {

        static OfferStatus of(OfferReason reason) {
            return new OfferStatus(reason == OfferReason.ELIGIBLE, reason, null, null, null);
        }
    }

    /**
     * Meme regle que {@link #eligible(User)}, avec la raison. L'offre deja recue passe en
     * premier : c'est l'information utile, meme si l'interrupteur a ete coupe depuis.
     */
    @Transactional(readOnly = true)
    public OfferStatus explain(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur"));
        List<Long> received = named.queryForList(
                "SELECT COALESCE(session_id, 0) FROM solo_free_offers WHERE user_id = :user",
                new MapSqlParameterSource("user", userId), Long.class);
        if (!received.isEmpty()) {
            Long session = received.get(0) == 0 ? null : received.get(0);
            return new OfferStatus(false, OfferReason.RECEIVED, session, null, null);
        }
        if (!playerAppService.firstSoloFreeEnabled()) {
            return OfferStatus.of(OfferReason.DISABLED);
        }
        if (user.isTest()) {
            return OfferStatus.of(OfferReason.TEST);
        }
        if (fraudProfileService.adjustmentFor(userId).isPresent()) {
            return OfferStatus.of(OfferReason.FRAUD);
        }
        if (paidSolo(List.of(userId)).isPresent()) {
            return OfferStatus.of(OfferReason.PAID);
        }
        List<Long> others = deviceLinkService.linkedGroup(userId).stream()
                .filter(id -> !id.equals(userId)).toList();
        if (!others.isEmpty()) {
            List<Long> offered = named.queryForList(
                    "SELECT user_id FROM solo_free_offers WHERE user_id IN (:group) ORDER BY created_at LIMIT 1",
                    new MapSqlParameterSource("group", others), Long.class);
            if (!offered.isEmpty()) {
                return linked(OfferReason.LINKED_RECEIVED, offered.get(0));
            }
            Optional<Long> payer = paidSolo(others);
            if (payer.isPresent()) {
                return linked(OfferReason.LINKED_PAID, payer.get());
            }
        }
        return OfferStatus.of(OfferReason.ELIGIBLE);
    }

    private Optional<Long> paidSolo(List<Long> group) {
        return named.queryForList(
                "SELECT user_id FROM game_sessions WHERE user_id IN (:group)"
                        + " AND game_mode = 'SOLO' AND is_test = FALSE AND entry_price_credits > 0"
                        + " ORDER BY started_at LIMIT 1",
                new MapSqlParameterSource("group", group), Long.class).stream().findFirst();
    }

    private OfferStatus linked(OfferReason reason, Long otherId) {
        String name = userRepository.findById(otherId)
                .map(User::fullName)
                .orElse(null);
        return new OfferStatus(false, reason, null, otherId, name);
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
