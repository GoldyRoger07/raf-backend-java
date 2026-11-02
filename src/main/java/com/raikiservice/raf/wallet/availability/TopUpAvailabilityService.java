package com.raikiservice.raf.wallet.availability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Disponibilite des agents pour les recharges manuelles, a la maniere du « en
 * ligne » de WhatsApp.
 *
 * <p>Un agent active l'interrupteur « Disponible pour les recharges » dans le
 * back-office ; tant qu'il reste active, le back-office envoie un signal toutes
 * les 30 secondes. Sans signal pendant {@link #TTL}, l'agent est considere hors
 * ligne : un navigateur ferme sans desactiver l'interrupteur ne laisse donc pas
 * croire aux joueurs qu'un agent est la.
 *
 * <p>L'etat vit en memoire : le backend tourne sur une seule instance, et un
 * redemarrage remet simplement tout le monde hors ligne jusqu'au prochain
 * signal. Avec plusieurs instances, il faudrait le partager (base ou Redis).
 */
@Service
public class TopUpAvailabilityService {

    /** Delai sans signal au-dela duquel un agent passe hors ligne. */
    static final Duration TTL = Duration.ofSeconds(120);

    private final Clock clock;

    /** Dernier signal de chaque agent disponible. */
    private final Map<Long, Instant> lastBeat = new ConcurrentHashMap<>();

    /** Dernier instant ou au moins un agent etait disponible (« vu a »). */
    private final AtomicReference<Instant> lastSeen = new AtomicReference<>();

    @Autowired
    public TopUpAvailabilityService() {
        this(Clock.systemUTC());
    }

    TopUpAvailabilityService(Clock clock) {
        this.clock = clock;
    }

    /** Ce que voient les joueurs ; {@code lastSeenAt} n'est donne qu'hors ligne. */
    public record Availability(boolean available, Instant lastSeenAt) {
    }

    /** L'agent est disponible (ou le reste). */
    public void heartbeat(Long agentId) {
        Instant now = clock.instant();
        lastBeat.put(agentId, now);
        lastSeen.set(now);
    }

    /** L'agent desactive son interrupteur ou se deconnecte. */
    public void goOffline(Long agentId) {
        Instant beat = lastBeat.remove(agentId);
        if (beat != null) {
            lastSeen.set(clock.instant());
        }
    }

    /** Vrai si cet agent est actuellement compte comme disponible. */
    public boolean isOnline(Long agentId) {
        Instant beat = lastBeat.get(agentId);
        return beat != null && fresh(beat, clock.instant());
    }

    public Availability availability() {
        Instant now = clock.instant();
        // Les agents dont le signal a expire sont retires ; « vu a » garde l'heure
        // de leur dernier signal, pas celle ou l'on s'en est apercu.
        lastBeat.entrySet().removeIf(entry -> !fresh(entry.getValue(), now));
        if (!lastBeat.isEmpty()) {
            return new Availability(true, null);
        }
        return new Availability(false, lastSeen.get());
    }

    private static boolean fresh(Instant beat, Instant now) {
        return Duration.between(beat, now).compareTo(TTL) < 0;
    }
}
