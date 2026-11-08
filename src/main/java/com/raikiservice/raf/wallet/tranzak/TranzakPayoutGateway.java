package com.raikiservice.raf.wallet.tranzak;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Payouts Tranzak : envoi d'argent sur un compte MonCash ou NatCash, depuis les
 * portefeuilles de versement prepayes du marchand. Deux temps : une autorisation
 * (rien n'est debite, jeton valable 5 minutes, a usage unique), puis l'envoi.
 * Interface pour que les tests remplacent la passerelle sans appel reseau.
 */
public interface TranzakPayoutGateway {

    /** Cle reelle autorisee aux payouts : les cles de test sont refusees par Tranzak. */
    boolean isConfigured();

    /** {@code network} : moncash ou natcash ; {@code receiver} : 509 + 8 chiffres. */
    Authorization authorize(NewPayout payout);

    /** Renvoyer le meme jeton apres une coupure rend le meme versement, sans second debit. */
    Payout send(String authorizationToken);

    Payout get(String payoutId);

    Optional<Payout> findByReference(String reference);

    record NewPayout(long amountHtg, String network, String receiver, String reference, String description) {
    }

    record Authorization(String token, BigDecimal fee, BigDecimal totalDebited) {
    }

    /**
     * Versement vu par Tranzak. {@code status} : processing, pending (verification
     * chez l'operateur, ne pas renvoyer), completed ou failed.
     */
    record Payout(String payoutId, String reference, String status, BigDecimal amount, BigDecimal fee,
            String failureCode, String failureMessage) {

        public boolean completed() {
            return "completed".equalsIgnoreCase(status);
        }

        public boolean failed() {
            return "failed".equalsIgnoreCase(status);
        }
    }
}
