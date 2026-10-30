package com.raikiservice.raf.common.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration applicative, alimentee par les fichiers .env.<env>.properties.
 * Aucun secret n'est ecrit en dur dans le code ou dans application.properties.
 */
@ConfigurationProperties("app")
public record AppProperties(
        String baseUrl,
        String frontendUrl,
        Jwt jwt,
        Security security,
        Mail mail,
        Sms sms,
        Otp otp,
        Verification verification,
        Media media,
        Push push,
        Ai ai) {

    /**
     * Generation de questions par IA. {@code provider} choisit l'adaptateur :
     * {@code none} (desactive), {@code anthropic} ou {@code openai} (toute API
     * compatible OpenAI : OpenAI, Mistral, DeepSeek, Groq, OpenRouter, Ollama...).
     * {@code baseUrl} vide = adresse officielle du fournisseur.
     */
    public record Ai(String provider, String model, String apiKey, String baseUrl, Duration timeout) {

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("CHANGE_ME");
        }
    }

    /** Web Push (VAPID). Sans cles, les push sont journalises au lieu d'etre envoyes. */
    public record Push(String vapidPublicKey, String vapidPrivateKey, String vapidSubject) {

        public boolean isConfigured() {
            return vapidPublicKey != null && !vapidPublicKey.isBlank()
                    && vapidPrivateKey != null && !vapidPrivateKey.isBlank()
                    && !vapidPublicKey.startsWith("CHANGE_ME");
        }
    }

    /**
     * Images stockees sur le disque du serveur. {@code publicUrl} : adresse a
     * laquelle le dossier {@code storageDir} est servi (nginx en Docker, le
     * backend lui-meme en developpement).
     */
    public record Media(String storageDir, String publicUrl) {
    }

    public record Jwt(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Security(Cors cors) {
        public record Cors(List<String> allowedOrigins) {
        }
    }

    public record Mail(String from, String fromName) {
    }

    /**
     * Envoi des SMS. {@code mode} : {@code console} (journalise, defaut) ou
     * {@code twilio}. {@code from} : numero Twilio (+1...), identifiant
     * alphanumerique (RAF) ou Messaging Service SID (MG...).
     */
    public record Sms(String mode, Twilio twilio) {

        public record Twilio(String accountSid, String authToken, String from) {

            public boolean isConfigured() {
                return filled(accountSid) && filled(authToken) && filled(from);
            }

            private static boolean filled(String value) {
                return value != null && !value.isBlank() && !value.startsWith("CHANGE_ME");
            }
        }
    }

    public record Otp(int length, Duration ttl, int maxAttempts, Duration resendCooldown) {
    }

    public record Verification(Duration emailTokenTtl, Duration passwordResetTtl) {
    }
}
