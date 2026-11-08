package com.raikiservice.raf.wallet.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.repository.WithdrawalRequestRepository;
import com.raikiservice.raf.wallet.tranzak.HttpTranzakPayoutGateway.PayoutJson;
import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway.Authorization;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway.NewPayout;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway.Payout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Retraits automatiques : a l'approbation d'un retrait MonCash / NatCash, RAF
 * envoie le montant net au numero du joueur par les payouts Tranzak.
 *
 * <ol>
 *   <li>le retrait passe en SENDING, avec une nouvelle reference RAF-WD-{id}-{essai} ;</li>
 *   <li>autorisation (rien n'est debite) puis envoi chez Tranzak ;</li>
 *   <li>confirmation par webhook {@code payout.completed} / {@code payout.failed}, ou
 *       par la verification planifiee ({@link #refreshSending}).</li>
 * </ol>
 *
 * <p>Verse : le retrait passe en PAID, le joueur est notifie. Echec : il revient
 * en APPROVED avec la raison ({@code payoutError}), les gains restent geles et
 * les administrateurs sont prevenus ; ils relancent l'envoi ({@link #send}),
 * versent a la main ou rejettent. Un envoi dont l'issue est inconnue (coupure)
 * reste en SENDING : jamais de second envoi tant que Tranzak n'a pas tranche.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoPayoutService {

    static final Duration CHECK_INTERVAL = Duration.ofSeconds(60);
    /**
     * Sans trace chez Tranzak passe ce delai, l'envoi n'a pas eu lieu : le jeton
     * d'autorisation (5 minutes) a expire, aucun versement ne peut plus en sortir.
     */
    static final Duration UNCONFIRMED_AFTER = Duration.ofMinutes(10);

    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final WithdrawalService withdrawalService;
    private final PayoutPolicy policy;
    private final TranzakPayoutGateway gateway;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** Configuration montree au joueur et au back-office. */
    public record Config(boolean enabled, long feeBps) {
    }

    public Config config() {
        return new Config(policy.active(), policy.feeBps());
    }

    /** Approbation par l'admin, suivie de l'envoi automatique quand il s'applique. */
    public WithdrawalRequest approve(Long adminId, Long withdrawalId) {
        WithdrawalRequest approved = withdrawalService.approve(adminId, withdrawalId);
        if (PayoutPolicy.eligible(approved) && policy.active()) {
            return send(adminId, withdrawalId);
        }
        return approved;
    }

    /** Envoi (ou nouvel essai) d'un retrait approuve. */
    public WithdrawalRequest send(Long adminId, Long withdrawalId) {
        if (!policy.active()) {
            throw new ApiException(ErrorCode.AUTO_PAYOUT_UNAVAILABLE);
        }
        NewPayout payout = transactionTemplate.execute(status -> {
            WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId)
                    .orElseThrow(() -> ApiException.notFound("Demande de retrait"));
            if (request.getStatus() != WithdrawalStatus.APPROVED) {
                throw new ApiException(ErrorCode.WITHDRAWAL_NOT_APPROVED);
            }
            if (!PayoutPolicy.eligible(request)) {
                throw new ApiException(ErrorCode.AUTO_PAYOUT_UNAVAILABLE,
                        "Seuls les retraits MonCash et NatCash partent automatiquement.");
            }
            request.setPayoutAttempts(request.getPayoutAttempts() + 1);
            request.setStatus(WithdrawalStatus.SENDING);
            request.setPayoutError(null);
            request.setPayoutId(null);
            request.setPayoutSentAt(Instant.now());
            request.setPayoutCheckedAt(null);
            auditService.record(adminId, "WITHDRAWAL_PAYOUT_SENT", "WithdrawalRequest", String.valueOf(withdrawalId),
                    null, request.getNetHtg() + " HTG vers " + request.getProvider() + " "
                            + request.getDestinationNumber() + " (" + request.currentPayoutReference() + ")");
            return new NewPayout(request.getNetHtg(), network(request.getProvider()),
                    receiver(request.getDestinationNumber()), request.currentPayoutReference(),
                    "Retrait RAF #" + withdrawalId);
        });

        Authorization authorization;
        try {
            authorization = gateway.authorize(payout);
        } catch (TranzakException e) {
            // L'autorisation ne debite rien : son echec est toujours definitif.
            fail(withdrawalId, describe(e.getCode(), e.getMessage()));
            return reload(withdrawalId);
        }

        Payout sent;
        try {
            sent = sendWithOneRetry(authorization.token());
        } catch (TranzakException e) {
            if (ambiguous(e)) {
                // Issue inconnue : la verification planifiee tranchera par la reference.
                log.warn("Retrait {} : envoi Tranzak sans reponse ({}), verification a suivre", withdrawalId,
                        e.getMessage());
            } else {
                fail(withdrawalId, describe(e.getCode(), e.getMessage()));
            }
            return reload(withdrawalId);
        }
        apply(withdrawalId, sent);
        return reload(withdrawalId);
    }

    /** Verification planifiee des envois en cours. */
    public int refreshSending() {
        Instant now = Instant.now();
        List<Long> ids = withdrawalRequestRepository.findSendingToCheck(now.minus(Duration.ofSeconds(30)),
                now.minus(CHECK_INTERVAL), PageRequest.of(0, 30));
        for (Long id : ids) {
            refresh(id);
        }
        return ids.size();
    }

    /** Webhook payout.completed / payout.failed, signature deja verifiee. */
    public void handleWebhook(String raw) {
        PayoutWebhook event;
        try {
            event = objectMapper.readValue(raw, PayoutWebhook.class);
        } catch (RuntimeException e) {
            log.error("Webhook payout Tranzak illisible : {}", e.getMessage());
            return;
        }
        if (event.data() == null) {
            log.warn("Webhook Tranzak {} sans versement, ignore", event.event());
            return;
        }
        Payout payout = event.data().toPayout();
        Optional<Long> id = locate(payout);
        if (id.isEmpty()) {
            log.warn("Webhook Tranzak {} : versement {} (reference {}) inconnu, ignore", event.event(),
                    payout.payoutId(), payout.reference());
            return;
        }
        apply(id.get(), payout);
    }

    void refresh(Long withdrawalId) {
        WithdrawalRequest request = withdrawalRequestRepository.findById(withdrawalId).orElse(null);
        if (request == null || request.getStatus() != WithdrawalStatus.SENDING) {
            return;
        }
        try {
            Optional<Payout> payout = request.getPayoutId() != null
                    ? Optional.of(gateway.get(request.getPayoutId()))
                    : gateway.findByReference(request.currentPayoutReference());
            if (payout.isPresent()) {
                apply(withdrawalId, payout.get());
            } else {
                failIfUnconfirmed(withdrawalId);
            }
        } catch (TranzakException e) {
            if (e.notFound()) {
                failIfUnconfirmed(withdrawalId);
            } else {
                log.warn("Retrait {} : verification du versement impossible ({})", withdrawalId, e.getMessage());
                touch(withdrawalId);
            }
        } catch (RuntimeException e) {
            log.error("Retrait {} : verification du versement en echec", withdrawalId, e);
            touch(withdrawalId);
        }
    }

    /** Applique l'etat d'un versement a son retrait, sous verrou. */
    private void apply(Long withdrawalId, Payout payout) {
        WithdrawalRequest failed = transactionTemplate.execute(status -> {
            WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId).orElseThrow();
            if (request.getStatus() != WithdrawalStatus.SENDING) {
                log.info("Retrait {} en {} : versement {} ({}) ignore", withdrawalId, request.getStatus(),
                        payout.payoutId(), payout.status());
                return null;
            }
            if (payout.reference() != null && !payout.reference().equals(request.currentPayoutReference())) {
                log.warn("Retrait {} : versement {} d'un essai precedent ({}), ignore", withdrawalId,
                        payout.payoutId(), payout.reference());
                return null;
            }
            if (request.getPayoutId() == null && payout.payoutId() != null) {
                request.setPayoutId(payout.payoutId());
            }
            request.setPayoutCheckedAt(Instant.now());

            if (payout.completed()) {
                request.setStatus(WithdrawalStatus.PAID);
                request.setPayoutReference(request.getPayoutId());
                request.setPaidAt(Instant.now());
                notificationService.notifyUser(request.getUser().getId(), NotificationMessages.withdrawalPaid(request));
                auditService.record(null, "WITHDRAWAL_PAID", "WithdrawalRequest", String.valueOf(withdrawalId), null,
                        "Versement automatique Tranzak " + request.getPayoutId());
                return null;
            }
            if (payout.failed()) {
                return markFailed(request, describe(payout.failureCode(), payout.failureMessage()));
            }
            return null;
        });
        notifyFailure(failed);
    }

    private void fail(Long withdrawalId, String reason) {
        WithdrawalRequest failed = transactionTemplate.execute(status -> {
            WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId).orElseThrow();
            return request.getStatus() == WithdrawalStatus.SENDING ? markFailed(request, reason) : null;
        });
        notifyFailure(failed);
    }

    private void failIfUnconfirmed(Long withdrawalId) {
        WithdrawalRequest failed = transactionTemplate.execute(status -> {
            WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId).orElseThrow();
            request.setPayoutCheckedAt(Instant.now());
            if (request.getStatus() == WithdrawalStatus.SENDING && request.getPayoutSentAt() != null
                    && request.getPayoutSentAt().isBefore(Instant.now().minus(UNCONFIRMED_AFTER))) {
                return markFailed(request, "envoi non confirme par Tranzak, aucun versement cree");
            }
            return null;
        });
        notifyFailure(failed);
    }

    /** Retour a l'admin : APPROVED, gains toujours geles. */
    private WithdrawalRequest markFailed(WithdrawalRequest request, String reason) {
        request.setStatus(WithdrawalStatus.APPROVED);
        request.setPayoutError(truncate(reason, 255));
        request.setPayoutCheckedAt(Instant.now());
        auditService.record(null, "WITHDRAWAL_PAYOUT_FAILED", "WithdrawalRequest", String.valueOf(request.getId()),
                null, request.currentPayoutReference() + " : " + reason);
        log.warn("Retrait {} : envoi automatique en echec ({})", request.getId(), reason);
        return request;
    }

    private void notifyFailure(WithdrawalRequest failed) {
        if (failed != null) {
            transactionTemplate.executeWithoutResult(status -> notificationService.notifyAdmins(
                    NotificationMessages.withdrawalPayoutFailed(
                            withdrawalRequestRepository.findById(failed.getId()).orElseThrow())));
        }
    }

    private void touch(Long withdrawalId) {
        transactionTemplate.executeWithoutResult(status -> withdrawalRequestRepository.findById(withdrawalId)
                .ifPresent(request -> request.setPayoutCheckedAt(Instant.now())));
    }

    /** Coupure ou erreur du serveur : renvoyer le meme jeton ne cree pas de second versement. */
    private Payout sendWithOneRetry(String token) {
        try {
            return gateway.send(token);
        } catch (TranzakException e) {
            if (!ambiguous(e)) {
                throw e;
            }
            log.warn("Envoi Tranzak sans reponse ({}), nouvel essai avec le meme jeton", e.getMessage());
            return gateway.send(token);
        }
    }

    private Optional<Long> locate(Payout payout) {
        if (payout.payoutId() != null) {
            Optional<WithdrawalRequest> found = withdrawalRequestRepository.findByPayoutId(payout.payoutId());
            if (found.isPresent()) {
                return Optional.of(found.get().getId());
            }
        }
        String reference = payout.reference();
        if (reference != null && reference.startsWith("RAF-WD-")) {
            String[] parts = reference.split("-");
            if (parts.length == 4) {
                try {
                    Long id = Long.valueOf(parts[2]);
                    if (withdrawalRequestRepository.existsById(id)) {
                        return Optional.of(id);
                    }
                } catch (NumberFormatException ignored) {
                    // Reference d'un autre systeme.
                }
            }
        }
        return Optional.empty();
    }

    private WithdrawalRequest reload(Long withdrawalId) {
        return transactionTemplate.execute(status -> {
            WithdrawalRequest request = withdrawalRequestRepository.findById(withdrawalId).orElseThrow();
            request.getUser().getId();
            return request;
        });
    }

    private static boolean ambiguous(TranzakException e) {
        return e.getHttpStatus() == 0 || e.getHttpStatus() >= 500;
    }

    static String network(PaymentProvider provider) {
        return provider == PaymentProvider.MONCASH ? "moncash" : "natcash";
    }

    /** Tranzak attend 8 chiffres ou 509 + 8 chiffres. */
    static String receiver(String destination) {
        String digits = destination == null ? "" : destination.replaceAll("\\D", "");
        return digits.length() == 8 ? "509" + digits : digits;
    }

    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("receiver_not_found", "numero inexistant sur ce reseau"),
            Map.entry("receiver_not_active", "compte du joueur inactif chez l'operateur"),
            Map.entry("receiver_not_enabled",
                    "compte MonCash non mis a niveau pour recevoir de l'argent (le joueur doit le faire chez Digicel)"),
            Map.entry("receiver_not_allowed", "compte du joueur non autorise a recevoir"),
            Map.entry("invalid_receiver", "numero de telephone invalide"),
            Map.entry("receiver_network_mismatch", "le numero n'appartient pas a ce reseau"),
            Map.entry("provider_unavailable", "operateur momentanement indisponible, relancez plus tard"),
            Map.entry("not_confirmed_by_provider", "envoi non confirme par l'operateur, relancez plus tard"),
            Map.entry("rejected_by_provider", "envoi refuse par l'operateur"),
            Map.entry("insufficient_wallet_balance",
                    "solde du portefeuille de versement Tranzak insuffisant : alimentez-le depuis la Tresorerie"),
            Map.entry("daily_limit_exceeded", "plafond journalier des versements Tranzak atteint"),
            Map.entry("amount_too_low", "montant inferieur au minimum Tranzak"),
            Map.entry("amount_below_minimum", "montant inferieur au minimum Tranzak"),
            Map.entry("amount_above_maximum", "montant superieur au maximum Tranzak"),
            Map.entry("payouts_frozen", "versements geles sur le compte Tranzak"),
            Map.entry("payouts_paused", "versements momentanement suspendus par Tranzak"),
            Map.entry("treasury_not_active", "tresorerie Tranzak inactive"),
            Map.entry("payouts_not_enabled_for_key", "cle API non autorisee aux versements dans Tranzak"),
            Map.entry("live_key_required", "les versements exigent une cle Tranzak reelle"));

    static String describe(String code, String message) {
        String reason = code == null ? null : REASONS.get(code);
        if (reason != null) {
            return reason;
        }
        String detail = message == null || message.isBlank() ? "erreur inconnue" : message;
        return code == null ? detail : detail + " (" + code + ")";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayoutWebhook(String event, PayoutJson data) {
    }
}
