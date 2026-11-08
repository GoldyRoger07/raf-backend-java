package com.raikiservice.raf.support;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakGateway;

/**
 * Passerelle Tranzak pilotee par les tests : garde les paiements crees, dont le
 * test change l'etat ({@link #settle}) comme le ferait le joueur chez MonCash.
 */
public class FakeTranzakGateway implements TranzakGateway {

    private final AtomicLong sequence = new AtomicLong(17_000_000_000_000L);
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, NewPayment> requests = new ConcurrentHashMap<>();
    private volatile boolean configured = true;
    /** Echec a lever a la prochaine creation, une seule fois. */
    private volatile TranzakException nextCreateFailure;
    /** Si vrai, la prochaine creation enregistre le paiement puis echoue (delai depasse). */
    private volatile boolean nextCreateTimesOutAfterCreating;

    @Override
    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public void failNextCreate(TranzakException failure) {
        this.nextCreateFailure = failure;
    }

    public void timeOutNextCreateAfterCreating() {
        this.nextCreateTimesOutAfterCreating = true;
    }

    @Override
    public Payment create(NewPayment payment) {
        TranzakException failure = nextCreateFailure;
        if (failure != null) {
            nextCreateFailure = null;
            throw failure;
        }
        String id = String.valueOf(sequence.incrementAndGet());
        Payment created = new Payment(id, "processing", BigDecimal.valueOf(payment.amountHtg()).setScale(2),
                "HTG", payment.reference(), "https://pay.tranzak.test/" + id, null);
        payments.put(id, created);
        requests.put(id, payment);
        if (nextCreateTimesOutAfterCreating) {
            nextCreateTimesOutAfterCreating = false;
            throw new TranzakException("Delai depasse", null, 0, null);
        }
        return created;
    }

    @Override
    public Payment get(String transactionId) {
        Payment payment = payments.get(transactionId);
        if (payment == null) {
            throw new TranzakException("introuvable", "transaction_not_found", 404, null);
        }
        return payment;
    }

    @Override
    public Optional<Payment> findByReference(String reference) {
        return payments.values().stream().filter(p -> reference.equals(p.reference())).findFirst();
    }

    /** Ce que RAF a demande pour ce paiement. */
    public NewPayment request(String transactionId) {
        return requests.get(transactionId);
    }

    public Payment payment(String transactionId) {
        return payments.get(transactionId);
    }

    /** Le joueur a paye (completed) ou abandonne (failed). */
    public Payment settle(String transactionId, String status) {
        Payment p = payments.get(transactionId);
        Payment settled = new Payment(p.transactionId(), status, p.amount(), p.currency(), p.reference(),
                p.paymentUrl(), "failed".equals(status) ? "Solde insuffisant" : null);
        payments.put(transactionId, settled);
        return settled;
    }
}
