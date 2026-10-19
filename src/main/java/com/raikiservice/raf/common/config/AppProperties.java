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
        Otp otp,
        Verification verification,
        ImageKit imagekit) {

    public record ImageKit(String publicKey, String privateKey, String urlEndpoint) {

        public boolean isConfigured() {
            return privateKey != null && !privateKey.isBlank()
                    && publicKey != null && !publicKey.isBlank();
        }
    }

    public record Jwt(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Security(Cors cors) {
        public record Cors(List<String> allowedOrigins) {
        }
    }

    public record Mail(String from, String resendApiKey) {
    }

    public record Otp(int length, Duration ttl, int maxAttempts, Duration resendCooldown) {
    }

    public record Verification(Duration emailTokenTtl, Duration passwordResetTtl) {
    }
}
