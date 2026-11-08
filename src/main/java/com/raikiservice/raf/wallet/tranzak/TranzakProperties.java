package com.raikiservice.raf.wallet.tranzak;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Passerelle de paiement Tranzak (MonCash, NatCash), alimentee par
 * TRANZAK_API_KEY et TRANZAK_WEBHOOK_SECRET. Sans cle, la recharge en ligne est
 * simplement indisponible : l'app ne la propose pas.
 */
@ConfigurationProperties("app.tranzak")
public record TranzakProperties(String apiKey, String webhookSecret, String baseUrl, Duration timeout,
        String payoutApiKey, String treasuryUrl) {

    /**
     * Payouts : cle reelle seulement (Tranzak refuse les cles de test), autorisee
     * dans le tableau de bord (Payouts > Security > Authorize).
     */
    public boolean payoutsConfigured() {
        return filled(payoutApiKey) && payoutApiKey.startsWith("tk_live_") && filled(treasuryUrl);
    }

    public boolean isConfigured() {
        return filled(apiKey) && filled(baseUrl);
    }

    public boolean hasWebhookSecret() {
        return filled(webhookSecret);
    }

    /** Une cle de test ne deplace pas d'argent reel. */
    public boolean isLive() {
        return apiKey != null && apiKey.startsWith("tk_live_");
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank() && !value.startsWith("CHANGE_ME");
    }
}
