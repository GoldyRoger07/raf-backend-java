package com.raikiservice.raf.wallet.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;
import com.raikiservice.raf.wallet.repository.TopUpRequestRepository;
import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakGateway;
import com.raikiservice.raf.wallet.tranzak.TranzakGateway.NewPayment;
import com.raikiservice.raf.wallet.tranzak.TranzakGateway.Payment;
import com.raikiservice.raf.wallet.tranzak.TranzakProperties;
import com.raikiservice.raf.wallet.tranzak.TranzakSignature;
import com.raikiservice.raf.wallet.tranzak.TranzakWebhookEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Recharge en ligne : le joueur paie par MonCash ou NatCash sur la page de la
 * passerelle Tranzak, sans agent.
 *
 * <ol>
 *   <li>{@link #start} enregistre une recharge PENDING, cree le paiement chez
 *       Tranzak et renvoie l'adresse ou envoyer le joueur ;</li>
 *   <li>Tranzak previent RAF par webhook ({@link #handleWebhook}), signe avec le
 *       secret du website ;</li>
 *   <li>en filet de securite, {@link #refreshPending} relit l'etat des recharges
 *       restees en attente (webhook perdu, ou MonCash en mode test, qui n'en
 *       envoie pas), et le retour du joueur dans l'app en force la lecture.</li>
 * </ol>
 *
 * <p>Les credits ne sont crees que par {@link TopUpService#confirm}, idempotent ;
 * webhook, verification planifiee et retour du joueur sont en plus serialises par
 * un verrou sur la recharge. Un paiement confirme apres l'abandon de la recharge
 * (echec ou expiration cote RAF) est tout de meme credite : l'argent est arrive.
 *
 * <p>Frais : le joueur les paie. Pour recevoir {@code montant} apres le
 * prelevement de la passerelle (p %), RAF facture
 * {@code montant + ceil(montant * p / (1 - p))}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineTopUpService {

    public static final Set<PaymentProvider> ONLINE_PROVIDERS = EnumSet.of(PaymentProvider.MONCASH,
            PaymentProvider.NATCASH);

    static final String REFERENCE_PREFIX = "RAF-TOPUP-";
    private static final long DEFAULT_FEE_BPS = 450;
    private static final long MAX_FEE_BPS = 5_000;
    private static final long DEFAULT_MIN_HTG = 50;
    private static final long DEFAULT_MAX_HTG = 25_000;

    /** Paiements en attente toleres par joueur sur {@link #PENDING_WINDOW}. */
    static final int MAX_PENDING_PER_USER = 3;
    static final Duration PENDING_WINDOW = Duration.ofMinutes(30);
    /** Delai entre deux lectures d'une meme recharge par la verification planifiee. */
    static final Duration CHECK_INTERVAL = Duration.ofSeconds(60);
    /** Lecture forcee par le joueur revenu dans l'app : pas plus souvent. */
    static final Duration PLAYER_CHECK_INTERVAL = Duration.ofSeconds(10);
    /**
     * Au-dela, une recharge toujours en attente expire. Tranzak declare en echec un
     * paiement non regle en 15 minutes ; la marge couvre un retard de confirmation.
     */
    static final Duration GIVE_UP_AFTER = Duration.ofMinutes(45);

    private final TopUpRequestRepository topUpRequestRepository;
    private final UserRepository userRepository;
    private final TopUpService topUpService;
    private final WalletService walletService;
    private final SettingsService settingsService;
    private final PlayerAppService playerAppService;
    private final TranzakGateway gateway;
    private final TranzakProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Ce que l'app joueur doit savoir pour proposer la recharge en ligne. */
    public record Config(boolean enabled, long feeBps, long minHtg, long maxHtg, long htgRate) {
    }

    public record Quote(long amountHtg, long feeHtg, long chargedHtg) {
    }

    public Config config() {
        long rate = walletService.htgRate();
        return new Config(playerAppService.onlineTopUpEnabled() && gateway.isConfigured(), feeBps(),
                minHtg(rate), maxHtg(), rate);
    }

    public Quote quote(long amountHtg) {
        long bps = feeBps();
        long fee = bps == 0 ? 0 : Math.ceilDiv(amountHtg * bps, 10_000 - bps);
        return new Quote(amountHtg, fee, amountHtg + fee);
    }

    /**
     * Lance une recharge en ligne de {@code amountHtg} (credites, hors frais).
     * Renvoie la recharge, dont {@code paymentUrl} est la page de paiement.
     */
    public TopUpRequest start(Long userId, long amountHtg, PaymentProvider provider) {
        if (provider == null || !ONLINE_PROVIDERS.contains(provider)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Choisis MonCash ou NatCash.");
        }
        Config config = config();
        if (!config.enabled()) {
            throw new ApiException(ErrorCode.ONLINE_TOPUP_DISABLED);
        }
        if (amountHtg < config.minHtg() || amountHtg > config.maxHtg()) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "Le montant doit etre compris entre "
                    + config.minHtg() + " et " + config.maxHtg() + " HTG.");
        }
        long pending = topUpRequestRepository.countByUserIdAndProviderInAndStatusAndCreatedAtAfter(userId,
                ONLINE_PROVIDERS, TopUpStatus.PENDING, Instant.now().minus(PENDING_WINDOW));
        if (pending >= MAX_PENDING_PER_USER) {
            throw new ApiException(ErrorCode.ONLINE_TOPUP_TOO_MANY_PENDING);
        }

        Quote quote = quote(amountHtg);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur"));
        Long topUpId = transactionTemplate.execute(status -> {
            TopUpRequest request = new TopUpRequest();
            request.setUser(user);
            request.setProvider(provider);
            request.setAmountHtg(amountHtg);
            // Estimation : le nombre exact de credits, retenue comprise, est fixe a la confirmation.
            request.setCredits(Math.max(1, amountHtg / config.htgRate()));
            request.setFeeHtg(quote.feeHtg());
            request.setChargedHtg(quote.chargedHtg());
            request.setStatus(TopUpStatus.PENDING);
            return topUpRequestRepository.save(request).getId();
        });

        Payment payment;
        try {
            payment = gateway.create(new NewPayment(quote.chargedHtg(), method(provider), customerName(user),
                    user.getPhone(), user.getEmail(), REFERENCE_PREFIX + topUpId,
                    "Recharge RAF de " + amountHtg + " HTG (+" + quote.feeHtg() + " HTG de frais)",
                    Map.of("topup_id", topUpId, "user_id", userId)));
        } catch (TranzakException e) {
            log.warn("Recharge en ligne {} : paiement non cree ({})", topUpId, e.getMessage());
            if (e.getHttpStatus() == 0) {
                // Delai depasse ou connexion coupee : le paiement existe peut-etre chez
                // Tranzak. La recharge reste en attente, la verification planifiee la
                // retrouvera par sa reference (ou la fera expirer).
                throw new ApiException(ErrorCode.ONLINE_TOPUP_UNAVAILABLE);
            }
            transactionTemplate.executeWithoutResult(status -> topUpService.markFailed(topUpId, null,
                    "Paiement non cree par la passerelle" + (e.getCode() == null ? "" : " (" + e.getCode() + ")")));
            if ("amount_too_low".equals(e.getCode()) || "amount_too_high".equals(e.getCode())) {
                throw new ApiException(ErrorCode.INVALID_AMOUNT,
                        "Ce montant n'est pas accepte par " + label(provider) + ".");
            }
            throw new ApiException(ErrorCode.ONLINE_TOPUP_UNAVAILABLE);
        }

        return transactionTemplate.execute(status -> {
            TopUpRequest request = topUpRequestRepository.findByIdForUpdate(topUpId).orElseThrow();
            request.setProviderRef(payment.transactionId());
            request.setPaymentUrl(payment.paymentUrl());
            auditService.record(userId, "TOPUP_ONLINE_STARTED", "TopUpRequest", String.valueOf(topUpId), null,
                    amountHtg + " HTG + " + quote.feeHtg() + " HTG de frais par " + provider
                            + " (Tranzak " + payment.transactionId() + ")");
            return request;
        });
    }

    /**
     * Webhook Tranzak. Une signature absente ou fausse est refusee (403) ; un
     * paiement inconnu est accepte puis ignore, pour que Tranzak cesse de le renvoyer.
     */
    public void handleWebhook(byte[] body, String signature) {
        if (!TranzakSignature.verify(properties.webhookSecret(), body, signature)) {
            log.warn("Webhook Tranzak refuse : signature absente ou invalide");
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Signature invalide.");
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        TranzakWebhookEvent event;
        try {
            event = objectMapper.readValue(raw, TranzakWebhookEvent.class);
        } catch (RuntimeException e) {
            log.error("Webhook Tranzak illisible : {}", e.getMessage());
            return;
        }
        if (event.data() == null) {
            log.warn("Webhook Tranzak {} sans paiement, ignore", event.event());
            return;
        }
        Payment payment = event.data().toPayment();
        if ("payment.refunded".equals(event.event())) {
            // Les remboursements ne concernent que les cartes, que RAF n'utilise pas.
            log.warn("Webhook Tranzak : remboursement du paiement {} (reference {}), a examiner",
                    payment.transactionId(), payment.reference());
            return;
        }
        Optional<Long> topUpId = locate(payment);
        if (topUpId.isEmpty()) {
            log.warn("Webhook Tranzak {} : paiement {} (reference {}) inconnu, ignore", event.event(),
                    payment.transactionId(), payment.reference());
            return;
        }
        apply(topUpId.get(), payment, raw);
    }

    /** Verification planifiee des recharges en ligne restees en attente. */
    public int refreshPending() {
        Instant now = Instant.now();
        List<Long> ids = topUpRequestRepository.findOnlinePendingToCheck(ONLINE_PROVIDERS,
                now.minus(CHECK_INTERVAL), now.minus(CHECK_INTERVAL), PageRequest.of(0, 30));
        for (Long id : ids) {
            refresh(id);
        }
        return ids.size();
    }

    /**
     * Recharge vue par son joueur. Revenu de la page de paiement, il demande ou en
     * est sa recharge : si elle attend encore, l'etat est relu chez Tranzak.
     */
    public TopUpRequest refreshForPlayer(Long userId, Long topUpId) {
        TopUpRequest request = topUpRequestRepository.findById(topUpId)
                .filter(t -> t.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Recharge"));
        Instant now = Instant.now();
        if (request.getStatus() == TopUpStatus.PENDING && request.getChargedHtg() != null
                && (request.getCheckedAt() == null
                        || request.getCheckedAt().isBefore(now.minus(PLAYER_CHECK_INTERVAL)))) {
            refresh(topUpId);
            request = topUpRequestRepository.findById(topUpId).orElseThrow();
        }
        return request;
    }

    /** Relit l'etat d'une recharge chez Tranzak et l'applique. Ne leve jamais. */
    void refresh(Long topUpId) {
        TopUpRequest request = topUpRequestRepository.findById(topUpId).orElse(null);
        if (request == null || request.getStatus() != TopUpStatus.PENDING || request.getChargedHtg() == null) {
            return;
        }
        try {
            Optional<Payment> payment = request.getProviderRef() != null
                    ? Optional.of(gateway.get(request.getProviderRef()))
                    : gateway.findByReference(REFERENCE_PREFIX + topUpId);
            if (payment.isPresent()) {
                apply(topUpId, payment.get(), null);
            } else {
                // Creation interrompue et inconnue de Tranzak : rien ne sera paye.
                expireIfStale(topUpId, "Paiement introuvable chez la passerelle");
            }
        } catch (TranzakException e) {
            log.warn("Recharge en ligne {} : verification impossible ({})", topUpId, e.getMessage());
            if (e.notFound()) {
                expireIfStale(topUpId, "Paiement introuvable chez la passerelle");
            } else {
                touch(topUpId);
            }
        } catch (RuntimeException e) {
            log.error("Recharge en ligne {} : verification en echec", topUpId, e);
            touch(topUpId);
        }
    }

    /** Applique l'etat d'un paiement a sa recharge, sous verrou. */
    private void apply(Long topUpId, Payment payment, String rawPayload) {
        transactionTemplate.executeWithoutResult(status -> {
            TopUpRequest request = topUpRequestRepository.findByIdForUpdate(topUpId).orElseThrow();
            if (request.getChargedHtg() == null) {
                log.warn("Recharge {} : pas une recharge en ligne, paiement {} ignore", topUpId,
                        payment.transactionId());
                return;
            }
            if (request.getProviderRef() == null && payment.transactionId() != null) {
                request.setProviderRef(payment.transactionId());
            } else if (payment.transactionId() != null && !payment.transactionId().equals(request.getProviderRef())) {
                log.error("Recharge {} : le paiement {} ne correspond pas a {}", topUpId, payment.transactionId(),
                        request.getProviderRef());
                return;
            }
            request.setCheckedAt(Instant.now());
            String raw = rawPayload != null ? rawPayload : describe(payment);

            if (payment.completed()) {
                if (request.getStatus() == TopUpStatus.CONFIRMED) {
                    return;
                }
                if (!amountMatches(request, payment)) {
                    String note = "Montant recu inattendu : " + payment.amount() + " " + payment.currency()
                            + " au lieu de " + request.getChargedHtg() + " HTG, a verifier";
                    if (!note.equals(request.getNote())) {
                        request.setNote(note);
                        log.error("Recharge {} : {}", topUpId, note);
                        auditService.record(null, "TOPUP_AMOUNT_MISMATCH", "TopUpRequest",
                                String.valueOf(topUpId), null, note);
                    }
                    return;
                }
                if (request.getStatus() != TopUpStatus.PENDING) {
                    // Paiement arrive apres l'abandon de la recharge : l'argent est la.
                    auditService.record(null, "TOPUP_LATE_PAYMENT", "TopUpRequest", String.valueOf(topUpId),
                            request.getStatus().name(), "Paiement " + payment.transactionId() + " confirme");
                    request.setStatus(TopUpStatus.PENDING);
                }
                topUpService.confirm(topUpId, null, raw);
            } else if (payment.failed()) {
                if (request.getStatus() == TopUpStatus.PENDING) {
                    request.setRawPayload(raw);
                    topUpService.markFailed(topUpId, null, "Paiement non abouti"
                            + (payment.failureReason() == null ? "" : " : " + truncate(payment.failureReason(), 200)));
                }
            } else if (request.getStatus() == TopUpStatus.PENDING && stale(request)) {
                expire(request, "Paiement non regle dans les delais");
            }
        });
    }

    private Optional<Long> locate(Payment payment) {
        String reference = payment.reference();
        if (reference != null && reference.startsWith(REFERENCE_PREFIX)) {
            try {
                Long id = Long.valueOf(reference.substring(REFERENCE_PREFIX.length()));
                if (topUpRequestRepository.existsById(id)) {
                    return Optional.of(id);
                }
            } catch (NumberFormatException ignored) {
                // Reference d'un autre systeme : recherche par identifiant Tranzak.
            }
        }
        if (payment.transactionId() == null) {
            return Optional.empty();
        }
        for (PaymentProvider provider : ONLINE_PROVIDERS) {
            Optional<TopUpRequest> found = topUpRequestRepository.findByProviderAndProviderRef(provider,
                    payment.transactionId());
            if (found.isPresent()) {
                return Optional.of(found.get().getId());
            }
        }
        return Optional.empty();
    }

    private void expireIfStale(Long topUpId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            TopUpRequest request = topUpRequestRepository.findByIdForUpdate(topUpId).orElseThrow();
            request.setCheckedAt(Instant.now());
            if (request.getStatus() == TopUpStatus.PENDING && stale(request)) {
                expire(request, reason);
            }
        });
    }

    private void expire(TopUpRequest request, String reason) {
        request.setStatus(TopUpStatus.EXPIRED);
        request.setNote(reason);
        auditService.record(null, "TOPUP_EXPIRED", "TopUpRequest", String.valueOf(request.getId()), null, reason);
    }

    private void touch(Long topUpId) {
        transactionTemplate.executeWithoutResult(status -> topUpRequestRepository.findById(topUpId)
                .ifPresent(request -> request.setCheckedAt(Instant.now())));
    }

    private static boolean stale(TopUpRequest request) {
        return request.getCreatedAt() != null && request.getCreatedAt().isBefore(Instant.now().minus(GIVE_UP_AFTER));
    }

    /** Le joueur doit avoir paye au moins le montant facture, en gourdes. */
    private static boolean amountMatches(TopUpRequest request, Payment payment) {
        boolean htg = payment.currency() == null || "HTG".equalsIgnoreCase(payment.currency());
        return htg && payment.amount() != null
                && payment.amount().compareTo(BigDecimal.valueOf(request.getChargedHtg())) >= 0;
    }

    private long feeBps() {
        long bps = settingsService.getLong(SettingKeys.TOPUP_ONLINE_FEE_BPS, DEFAULT_FEE_BPS);
        return Math.clamp(bps, 0, MAX_FEE_BPS);
    }

    /** Au moins un credit : une recharge confirmee doit en creer. */
    private long minHtg(long rate) {
        return Math.max(rate, settingsService.getLong(SettingKeys.TOPUP_ONLINE_MIN_HTG, DEFAULT_MIN_HTG));
    }

    private long maxHtg() {
        return settingsService.getLong(SettingKeys.TOPUP_ONLINE_MAX_HTG, DEFAULT_MAX_HTG);
    }

    private static String method(PaymentProvider provider) {
        return provider == PaymentProvider.MONCASH ? "moncash" : "natcash";
    }

    private static String label(PaymentProvider provider) {
        return provider == PaymentProvider.MONCASH ? "MonCash" : "NatCash";
    }

    private static String customerName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? "Joueur RAF " + user.getId() : name;
    }

    private static String describe(Payment payment) {
        return "Tranzak " + payment.transactionId() + " : " + payment.status() + ", " + payment.amount() + " "
                + payment.currency();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
