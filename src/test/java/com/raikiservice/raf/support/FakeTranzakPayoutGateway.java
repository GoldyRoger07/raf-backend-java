package com.raikiservice.raf.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway;

/**
 * Payouts Tranzak pilotes par les tests. Desactives par defaut : les tests de
 * retrait existants gardent le versement manuel ; AutoPayoutTest les active.
 */
public class FakeTranzakPayoutGateway implements TranzakPayoutGateway {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, NewPayout> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Payout> payouts = new ConcurrentHashMap<>();
    private final Map<String, NewPayout> sentRequests = new ConcurrentHashMap<>();
    /** Nombre d'envois effectifs (un jeton renvoye ne compte qu'une fois). */
    private final AtomicInteger created = new AtomicInteger();
    private volatile boolean configured;
    private volatile TranzakException nextAuthorizeFailure;
    /** L'envoi suivant cree le versement puis ne repond pas (coupure), {@code times} fois. */
    private volatile int sendTimeouts;

    @Override
    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public void failNextAuthorize(TranzakException failure) {
        this.nextAuthorizeFailure = failure;
    }

    public void timeOutNextSends(int times) {
        this.sendTimeouts = times;
    }

    public int createdCount() {
        return created.get();
    }

    @Override
    public Authorization authorize(NewPayout payout) {
        TranzakException failure = nextAuthorizeFailure;
        if (failure != null) {
            nextAuthorizeFailure = null;
            throw failure;
        }
        String token = "ptk_" + sequence.incrementAndGet();
        authorizations.put(token, payout);
        BigDecimal fee = BigDecimal.valueOf(payout.amountHtg()).multiply(new BigDecimal("0.035"))
                .setScale(2, RoundingMode.HALF_UP);
        return new Authorization(token, fee, BigDecimal.valueOf(payout.amountHtg()).add(fee));
    }

    @Override
    public Payout send(String authorizationToken) {
        NewPayout request = authorizations.get(authorizationToken);
        if (request == null) {
            throw new TranzakException("jeton inconnu", "invalid_authorization", 404, null);
        }
        String payoutId = "PAY_" + authorizationToken;
        Payout payout = payouts.computeIfAbsent(payoutId, id -> {
            created.incrementAndGet();
            sentRequests.put(id, request);
            return new Payout(id, request.reference(), "processing", BigDecimal.valueOf(request.amountHtg()),
                    null, null, null);
        });
        if (sendTimeouts > 0) {
            sendTimeouts--;
            throw new TranzakException("Delai depasse", null, 0, null);
        }
        return payout;
    }

    @Override
    public Payout get(String payoutId) {
        Payout payout = payouts.get(payoutId);
        if (payout == null) {
            throw new TranzakException("introuvable", "payout_not_found", 404, null);
        }
        return payout;
    }

    @Override
    public Optional<Payout> findByReference(String reference) {
        return payouts.values().stream().filter(p -> reference.equals(p.reference())).findFirst();
    }

    /** Ce que RAF a demande d'envoyer pour ce versement. */
    public NewPayout request(String payoutId) {
        return sentRequests.get(payoutId);
    }

    public Optional<Payout> byReference(String reference) {
        return findByReference(reference);
    }

    /** L'operateur a verse (completed) ou refuse (failed, avec son code). */
    public Payout settle(String payoutId, String status, String failureCode) {
        Payout p = payouts.get(payoutId);
        Payout settled = new Payout(p.payoutId(), p.reference(), status, p.amount(), p.fee(), failureCode,
                failureCode == null ? null : "Echec " + failureCode);
        payouts.put(payoutId, settled);
        return settled;
    }
}
