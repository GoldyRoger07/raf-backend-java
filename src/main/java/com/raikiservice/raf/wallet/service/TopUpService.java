package com.raikiservice.raf.wallet.service;

import java.time.Instant;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpRequest;
import com.raikiservice.raf.wallet.domain.TopUpStatus;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.repository.TopUpRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recharges de compte.
 *
 * <p>Au lot 3, seule la recharge manuelle par un administrateur est disponible :
 * l'agent encaisse hors ligne puis credite le compte. Les connecteurs MonCash et
 * NatCash du lot 8 reutiliseront {@link #confirm} depuis leur webhook, ce qui
 * garantit qu'il n'existe qu'un seul chemin pour creer des credits. La recharge
 * avec les gains ({@link #convertWinnings}) y passe aussi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopUpService {

    private final TopUpRequestRepository topUpRequestRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final TopUpRetentionService retentionService;
    private final PlayerAppService playerAppService;

    @Transactional
    public TopUpRequest createManual(Long adminId, Long userId, long amountHtg, String reference,
            String note) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));

        if (amountHtg <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT);
        }
        // Estimation : le nombre exact de credits, retenue comprise, est fixe a la confirmation.
        long credits = amountHtg / walletService.htgRate();

        if (reference != null && !reference.isBlank()) {
            topUpRequestRepository.findByProviderAndProviderRef(PaymentProvider.MANUAL, reference)
                    .ifPresent(existing -> {
                        throw new ApiException(ErrorCode.TOPUP_ALREADY_PROCESSED,
                                "Une recharge portant la reference " + reference + " existe deja.");
                    });
        }

        TopUpRequest request = new TopUpRequest();
        request.setUser(user);
        request.setProvider(PaymentProvider.MANUAL);
        request.setAmountHtg(amountHtg);
        request.setCredits(credits);
        request.setStatus(TopUpStatus.PENDING);
        request.setProviderRef(reference);
        request.setNote(note);

        TopUpRequest saved = topUpRequestRepository.save(request);
        return confirm(saved.getId(), adminId, null);
    }

    /**
     * Recharge avec les gains : le joueur paie des credits avec son solde de gains (HTG).
     *
     * <p>Seules les gourdes qui forment des credits entiers sont debitees : 125 HTG
     * demandes a 10 HTG le credit donnent 12 credits pour 120 HTG, les 5 HTG restent
     * dans les gains (toujours retirables). La retenue de recharge n'intervient pas.
     * Ni frais ni bonus : le taux est celui de credit.htg_rate.
     *
     * <p>Le debit des gains verrouille le portefeuille : une requete concurrente
     * portant la meme cle attend, puis retrouve la conversion deja enregistree.
     */
    @Transactional
    public TopUpRequest convertWinnings(Long userId, long amountHtg, String requestKey) {
        String ref = "winnings:" + userId + ":" + requestKey;
        Optional<TopUpRequest> replay = topUpRequestRepository.findByProviderAndProviderRef(PaymentProvider.WINNINGS, ref);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (!playerAppService.winningsConversionEnabled()) {
            throw new ApiException(ErrorCode.WINNINGS_CONVERSION_DISABLED);
        }
        long rate = walletService.htgRate();
        long credits = amountHtg / rate;
        if (amountHtg <= 0 || credits < 1) {
            throw new ApiException(ErrorCode.WINNINGS_CONVERSION_TOO_SMALL,
                    "Il faut au moins " + rate + " HTG de gains pour obtenir 1 credit.");
        }
        long debitedHtg = credits * rate;

        WalletTransaction debit = walletService.debit(userId, BalanceType.WINNINGS, debitedHtg,
                WalletTransactionType.WINNINGS_CONVERSION, "User", String.valueOf(userId), ref,
                "Conversion de " + debitedHtg + " HTG de gains en " + credits + " credit(s)");

        replay = topUpRequestRepository.findByProviderAndProviderRef(PaymentProvider.WINNINGS, ref);
        if (replay.isPresent()) {
            return replay.get();
        }

        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur"));
        TopUpRequest request = new TopUpRequest();
        request.setUser(user);
        request.setProvider(PaymentProvider.WINNINGS);
        request.setAmountHtg(debitedHtg);
        request.setCredits(credits);
        request.setStatus(TopUpStatus.PENDING);
        request.setProviderRef(ref);
        request.setDebitTransactionId(debit.getId());
        TopUpRequest saved = topUpRequestRepository.save(request);

        // Le debit pointe vers l'operation qui l'explique.
        debit.setReferenceType("TopUpRequest");
        debit.setReferenceId(String.valueOf(saved.getId()));

        auditService.record(userId, "WINNINGS_CONVERTED", "TopUpRequest", String.valueOf(saved.getId()), null,
                debitedHtg + " HTG de gains convertis en " + credits + " credit(s) par l'utilisateur " + userId);

        return confirm(saved.getId(), null, null);
    }

    /**
     * Passe une recharge en CONFIRMED et credite le solde.
     *
     * <p>Unique endroit du code qui cree des credits. L'idempotence porte sur
     * l'identifiant de la recharge : un webhook rejoue ne credite pas deux fois.
     */
    @Transactional
    public TopUpRequest confirm(Long topUpId, Long adminId, String rawPayload) {
        TopUpRequest request = topUpRequestRepository.findById(topUpId)
                .orElseThrow(() -> ApiException.notFound("Recharge"));

        if (request.getStatus() == TopUpStatus.CONFIRMED) {
            log.info("Recharge {} deja confirmee, confirmation ignoree", topUpId);
            return request;
        }
        if (request.getStatus() != TopUpStatus.PENDING) {
            throw new ApiException(ErrorCode.TOPUP_ALREADY_PROCESSED);
        }

        // Retenue : les gourdes qui ne forment pas un credit entier passent a la
        // recharge suivante. 15 HTG a 10 HTG le credit : 1 credit et 5 HTG gardes ;
        // 17 HTG ensuite : 22 HTG, soit 2 credits et 2 HTG gardes.
        Long userId = request.getUser().getId();
        long rate = walletService.htgRate();
        // Une conversion de gains tombe juste (multiple du taux) et ne touche pas la retenue.
        boolean fromWinnings = request.getProvider() == PaymentProvider.WINNINGS;
        long used = fromWinnings ? 0 : retentionService.lock(userId);
        long total = request.getAmountHtg() + used;
        long credits = total / rate;
        long kept = total % rate;

        request.setHtgRate(rate);
        request.setCredits(credits);
        request.setRetentionUsedHtg(used);
        request.setRetentionKeptHtg(kept);

        if (credits > 0) {
            WalletTransaction transaction = walletService.credit(
                    userId,
                    BalanceType.CREDIT,
                    credits,
                    WalletTransactionType.TOPUP,
                    "TopUpRequest",
                    String.valueOf(request.getId()),
                    "topup:" + request.getId(),
                    fromWinnings
                            ? "Recharge de " + request.getAmountHtg() + " HTG avec les gains"
                            : "Recharge de " + request.getAmountHtg() + " HTG (" + request.getProvider() + ")"
                                    + (used > 0 ? " + " + used + " HTG de retenue" : ""));
            request.setTransactionId(transaction.getId());
        }
        if (used > 0) {
            retentionService.move(userId, -used, TopUpRetentionService.Reason.USED, request.getId(), adminId, null);
        }
        if (kept > 0) {
            retentionService.move(userId, kept, TopUpRetentionService.Reason.KEPT, request.getId(), adminId, null);
        }

        request.setStatus(TopUpStatus.CONFIRMED);
        request.setConfirmedAt(Instant.now());
        request.setConfirmedByAdminId(adminId);
        if (rawPayload != null) {
            request.setRawPayload(rawPayload);
        }

        notificationService.notifyUser(userId, fromWinnings
                ? NotificationMessages.winningsConverted(request.getId(), credits, request.getAmountHtg())
                : NotificationMessages.topUpConfirmed(request.getId(), credits, request.getAmountHtg(), used, kept));

        auditService.record(adminId, "TOPUP_CONFIRMED", "TopUpRequest",
                String.valueOf(request.getId()), null,
                request.getCredits() + " credits vers l'utilisateur " + request.getUser().getId()
                        + " (retenue reprise " + request.getRetentionUsedHtg() + " HTG, gardee "
                        + request.getRetentionKeptHtg() + " HTG)");

        return request;
    }

    @Transactional
    public TopUpRequest markFailed(Long topUpId, Long adminId, String reason) {
        TopUpRequest request = topUpRequestRepository.findById(topUpId)
                .orElseThrow(() -> ApiException.notFound("Recharge"));

        if (request.getStatus() != TopUpStatus.PENDING) {
            throw new ApiException(ErrorCode.TOPUP_ALREADY_PROCESSED);
        }

        request.setStatus(TopUpStatus.FAILED);
        request.setNote(reason);

        auditService.record(adminId, "TOPUP_FAILED", "TopUpRequest",
                String.valueOf(request.getId()), null, reason);

        return request;
    }

    @Transactional(readOnly = true)
    public Page<TopUpRequest> historyForUser(Long userId, Pageable pageable) {
        return topUpRequestRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<TopUpRequest> byStatus(TopUpStatus status, Pageable pageable) {
        return topUpRequestRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }
}
