package com.raikiservice.raf.support;

import java.sql.Connection;
import java.sql.SQLException;

import com.raikiservice.raf.notification.sms.SmsSender;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Infrastructure commune aux tests d'integration.
 *
 * <p>La base est remise a zero une fois par contexte Spring (donc une fois par
 * lancement, le contexte etant partage entre les classes de test).
 */
@TestConfiguration
@Import(TestData.class)
public class TestInfrastructure {

    /**
     * Efface puis recree le schema.
     *
     * <p><strong>Garde-fou :</strong> refuse d'effacer une base dont le nom ne finit
     * pas par {@code _test}. Une URL mal configuree ne doit jamais pouvoir vider
     * la base de developpement.
     */
    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            assertTestDatabase(flyway);
            flyway.clean();
            flyway.migrate();
        };
    }

    @Bean
    @Primary
    CapturingSmsSender capturingSmsSender() {
        return new CapturingSmsSender();
    }

    private static void assertTestDatabase(Flyway flyway) {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            String database = connection.getCatalog();
            if (database == null || !database.endsWith("_test")) {
                throw new IllegalStateException("Les tests refusent d'effacer la base '" + database
                        + "' : son nom doit finir par _test. Verifiez spring.datasource.url / TEST_DB_URL.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Base de test injoignable", e);
        }
    }

    /** Garde le dernier SMS de chaque numero : c'est la que les tests lisent les codes OTP. */
    public static class CapturingSmsSender implements SmsSender {

        private final java.util.Map<String, String> lastByPhone =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void send(String phone, String message) {
            lastByPhone.put(phone, message);
        }

        public String lastMessage(String phone) {
            return lastByPhone.get(phone);
        }

        /** Le code a 4-8 chiffres du dernier SMS envoye a ce numero. */
        public String lastCode(String phone) {
            String message = lastByPhone.get(phone);
            if (message == null) {
                return null;
            }
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("\\b(\\d{4,8})\\b").matcher(message);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}
