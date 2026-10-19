package com.raikiservice.raf.common.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Compteur de tentatives par fenetre glissante, en memoire.
 *
 * <p>Protege les endpoints ou un mot de passe ou un code peut etre devine par
 * repetition. Sans lui, rien n'empeche un robot d'essayer des milliers de mots de
 * passe sur /api/auth/login.
 *
 * <p><strong>Limite connue :</strong> l'etat vit dans la JVM. Le jour ou le backend
 * tournera sur plusieurs instances, chacune comptera de son cote et le plafond
 * reel sera multiplie par le nombre d'instances. Il faudra alors deplacer ce
 * compteur dans Redis.
 */
@Slf4j
@Component
public class RateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        private final Instant expiresAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    /** Refuse la requete si le plafond est deja atteint, sans rien compter. */
    public void assertNotBlocked(String key, int maxAttempts) {
        Window window = windows.get(key);
        if (window == null || window.isExpired()) {
            return;
        }
        if (window.count.get() >= maxAttempts) {
            log.warn("Plafond de tentatives atteint pour la cle {}", key);
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    /** Comptabilise une tentative infructueuse. */
    public void recordAttempt(String key, Duration window) {
        windows.compute(key, (ignored, existing) -> {
            Window current = (existing == null || existing.isExpired())
                    ? new Window(Instant.now().plus(window))
                    : existing;
            current.count.incrementAndGet();
            return current;
        });
    }

    /** Une operation reussie efface l'ardoise. */
    public void reset(String key) {
        windows.remove(key);
    }

    @Scheduled(fixedDelay = 600_000L)
    void purgeExpiredWindows() {
        windows.values().removeIf(Window::isExpired);
    }
}
