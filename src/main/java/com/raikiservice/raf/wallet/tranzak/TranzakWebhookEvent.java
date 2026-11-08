package com.raikiservice.raf.wallet.tranzak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Corps d'un webhook Tranzak : {@code event} vaut payment.success,
 * payment.failed ou payment.refunded ; {@code data} decrit le paiement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranzakWebhookEvent(String event, HttpTranzakGateway.PaymentJson data) {
}
