package com.raikiservice.raf.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import com.raikiservice.raf.notification.sms.SmsSender;
import com.raikiservice.raf.question.ai.AiGenerationException;
import com.raikiservice.raf.question.ai.GeneratedQuestion;
import com.raikiservice.raf.question.ai.GenerationRequest;
import com.raikiservice.raf.question.ai.QuestionGenerator;

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

    @Bean
    @Primary
    CapturingMailSender capturingMailSender() {
        return new CapturingMailSender();
    }

    @Bean
    @Primary
    CapturingPushSender capturingPushSender() {
        return new CapturingPushSender();
    }

    @Bean
    @Primary
    FakeQuestionGenerator fakeQuestionGenerator() {
        return new FakeQuestionGenerator();
    }

    /**
     * Generateur IA pilote par le test : renvoie les questions preparees, ou
     * l'echec demande, et garde la derniere demande recue.
     */
    public static class FakeQuestionGenerator implements QuestionGenerator {

        private List<GeneratedQuestion> next = List.of();
        private AiGenerationException failure;
        private boolean configured = true;
        private GenerationRequest lastRequest;

        /** A appeler en debut de test : le contexte (donc ce bean) est partage. */
        public void reset() {
            next = List.of();
            failure = null;
            configured = true;
            lastRequest = null;
        }

        public void willReturn(List<GeneratedQuestion> questions) {
            this.next = questions;
        }

        public void willFail(String message) {
            this.failure = new AiGenerationException(message);
        }

        public void setConfigured(boolean configured) {
            this.configured = configured;
        }

        public GenerationRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public List<GeneratedQuestion> generate(GenerationRequest request) {
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return next;
        }
    }

    /** Emails envoyes, par destinataire. */
    public static class CapturingMailSender implements com.raikiservice.raf.notification.mail.MailSender {

        public record Mail(String to, String subject, String html) {
        }

        private final java.util.List<Mail> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String htmlBody) {
            sent.add(new Mail(to, subject, htmlBody));
        }

        public java.util.List<Mail> to(String email) {
            return sent.stream().filter(mail -> mail.to().equals(email)).toList();
        }
    }

    /**
     * Push envoyes, par utilisateur. {@link #revoke} simule un navigateur qui a
     * retire son abonnement (reponse 410 du service push).
     */
    public static class CapturingPushSender implements com.raikiservice.raf.notification.push.PushSender {

        public record Push(Long userId, String endpoint, String payload) {
        }

        private final java.util.List<Push> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.Set<String> revoked = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public Result send(com.raikiservice.raf.notification.domain.PushSubscription subscription,
                String jsonPayload) {
            if (revoked.contains(subscription.getEndpoint())) {
                return Result.GONE;
            }
            sent.add(new Push(subscription.getUserId(), subscription.getEndpoint(), jsonPayload));
            return Result.SENT;
        }

        public void revoke(String endpoint) {
            revoked.add(endpoint);
        }

        public java.util.List<Push> to(Long userId) {
            return sent.stream().filter(push -> push.userId().equals(userId)).toList();
        }
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
