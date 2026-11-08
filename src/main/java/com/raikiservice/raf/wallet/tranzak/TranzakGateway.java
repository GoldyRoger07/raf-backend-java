package com.raikiservice.raf.wallet.tranzak;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Ce que RAF demande a Tranzak. Interface pour que les tests remplacent la
 * passerelle sans aucun appel reseau.
 */
public interface TranzakGateway {

    boolean isConfigured();

    /**
     * Cle reelle (tk_live_). Avec une cle de test, Tranzak simule les paiements :
     * NatCash se confirme tout seul, sans argent. RAF reserve alors la recharge en
     * ligne aux comptes de test (OnlineTopUpService).
     */
    boolean isLive();

    /** Cree un paiement ; le joueur le regle en ouvrant {@link Payment#paymentUrl()}. */
    Payment create(NewPayment payment);

    /** Etat actuel d'un paiement. */
    Payment get(String transactionId);

    /** Paiement portant cette reference, si Tranzak en connait un. */
    Optional<Payment> findByReference(String reference);

    /** {@code method} : moncash ou natcash. Montant entier en HTG. */
    record NewPayment(long amountHtg, String method, String customerName, String customerPhone,
            String customerEmail, String reference, String description, Map<String, Object> metadata) {
    }

    /**
     * Paiement vu par Tranzak. {@code status} : pending, processing, completed,
     * failed ou refunded.
     */
    record Payment(String transactionId, String status, BigDecimal amount, String currency,
            String reference, String paymentUrl, String failureReason) {

        public boolean completed() {
            return "completed".equalsIgnoreCase(status);
        }

        public boolean failed() {
            return "failed".equalsIgnoreCase(status);
        }
    }
}
