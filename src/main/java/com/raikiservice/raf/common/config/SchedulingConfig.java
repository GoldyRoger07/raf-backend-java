package com.raikiservice.raf.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active les ordonnanceurs (avancement des quiz, expiration des parties, purge).
 *
 * <p>Desactivable par {@code app.scheduling.enabled=false} : les tests
 * d'integration pilotent eux-memes les transitions, et un ordonnanceur qui se
 * declenche au milieu d'un test le rendrait aleatoire.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
