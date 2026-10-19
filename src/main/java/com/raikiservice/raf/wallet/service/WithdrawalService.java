package com.raikiservice.raf.wallet.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.OtpService;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.repository.WithdrawalRequestRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retraits de gains.
 *
 * <p>Trois controles avant qu'un centime ne bouge : telephone verifie par OTP,
 * montant au-dessus du minimum, plafond journalier non depasse. Les gains sont
 * ensuite geles immediatement, et rendus si la demande est rejetee.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final long DEFAULT_MIN_CREDITS = 200L;
    private static final long DEFAULT_MAX_PER_DAY = 20_000L;
    private static final long DEFAULT_FEE_HTG = 25L;

    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final OtpService otpService;
    private final SettingsService settingsService;
    private final AuditService auditService;

    /** Envoie le code de confirmation sur le telephone verifie du joueur. */
    @Transactional
    public void sendWithdrawalOtp(Long userId) {
        User user = loadUser(userId);
        assertPhoneVerified(user);
        otpService.send(user, OtpPurpose.WITHDRAWAL, user.getPhone());
    }

    @Transactional
    public WithdrawalRequest request(Long userId, long amountCredits, PaymentProvider provider,
            String destinationNumber, String otpCode) {

        User user = loadUser(userId);
        assertPhoneVerified(user);

        // Especes : pas de numero de reception, l'argent est remis au guichet.
        boolean cash = provider == PaymentProvider.MANUAL;
        if (!cash && (destinationNumber == null || destinationNumber.isBlank())) {
            throw new ApiException(ErrorCode.WITHDRAWAL_DESTINATION_REQUIRED);
        }

        // Une seule demande en attente a la fois : sinon l'administrateur traite
        // des demandes qui se chevauchent sur le meme solde.
        if (!withdrawalRequestRepository.findByUserIdAndStatus(userId, WithdrawalStatus.PENDING)
                .isEmpty()) {
            throw new ApiException(ErrorCode.WITHDRAWAL_PENDING_EXISTS);
        }

        assertWithinLimits(userId, amountCredits);

        long amountHtg = walletService.creditsToHtg(amountCredits);
        long feeHtg = cash
                ? settingsService.getLong(SettingKeys.WITHDRAWAL_COUNTER_FEE_HTG, DEFAULT_FEE_HTG)
                : settingsService.getLong(SettingKeys.WITHDRAWAL_FEE_HTG, DEFAULT_FEE_HTG);
        if (feeHtg >= amountHtg) {
            throw new ApiException(ErrorCode.WITHDRAWAL_BELOW_MINIMUM,
                    "Le montant demande ne couvre pas les frais de " + feeHtg + " HTG.");
        }

        // Verifie en dernier, juste avant le debit : tout echec anterieur annulerait
        // la consommation du code et obligerait le joueur a en redemander un.
        otpService.verify(user, OtpPurpose.WITHDRAWAL, otpCode);

        WithdrawalRequest request = new WithdrawalRequest();
        request.setUser(user);
        request.setAmountCredits(amountCredits);
        request.setAmountHtg(amountHtg);
        request.setFeeHtg(feeHtg);
        request.setNetHtg(amountHtg - feeHtg);
        request.setProvider(provider);
        request.setDestinationNumber(cash ? null : destinationNumber);
        request.setStatus(WithdrawalStatus.PENDING);
        request.setOtpVerifiedAt(Instant.now());

        WithdrawalRequest saved = withdrawalRequestRepository.save(request);

        // Gel immediat : le solde ne doit plus pouvoir servir ailleurs.
        WalletTransaction debit = walletService.debit(
                userId,
                BalanceType.WINNINGS,
                amountCredits,
                WalletTransactionType.WITHDRAWAL,
                "WithdrawalRequest",
                String.valueOf(saved.getId()),
                "withdrawal:" + saved.getId(),
                "Demande de retrait de " + amountHtg + " HTG");

        saved.setDebitTransactionId(debit.getId());

        auditService.record(userId, "WITHDRAWAL_REQUESTED", "WithdrawalRequest",
                String.valueOf(saved.getId()), null, amountCredits + " credits");

        return saved;
    }

    /**
     * Minimum et plafond journalier, communs au retrait en ligne et au retrait au
     * guichet : passer par le guichet ne doit pas permettre de contourner le
     * plafond.
     */
    @Transactional(readOnly = true)
    public void assertWithinLimits(Long userId, long amountCredits) {
        if (amountCredits <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT);
        }

        long minCredits = settingsService.getLong(
                SettingKeys.WITHDRAWAL_MIN_CREDITS, DEFAULT_MIN_CREDITS);
        if (amountCredits < minCredits) {
            throw new ApiException(ErrorCode.WITHDRAWAL_BELOW_MINIMUM,
                    "Le retrait minimum est de " + minCredits + " credits ("
                            + walletService.creditsToHtg(minCredits) + " HTG).");
        }

        long maxPerDay = settingsService.getLong(
                SettingKeys.WITHDRAWAL_MAX_CREDITS_PER_DAY, DEFAULT_MAX_PER_DAY);
        long alreadyRequested = withdrawalRequestRepository.sumRequestedSince(
                userId, Instant.now().minus(1, ChronoUnit.DAYS));
        if (alreadyRequested + amountCredits > maxPerDay) {
            throw new ApiException(ErrorCode.WITHDRAWAL_DAILY_LIMIT,
                    "Plafond journalier de " + maxPerDay + " credits : il reste "
                            + Math.max(0, maxPerDay - alreadyRequested) + " credit(s).");
        }
    }

    @Transactional
    public WithdrawalRequest approve(Long adminId, Long withdrawalId) {
        WithdrawalRequest request = loadPending(withdrawalId);

        request.setStatus(WithdrawalStatus.APPROVED);
        request.setReviewedByAdminId(adminId);
        request.setReviewedAt(Instant.now());

        auditService.record(adminId, "WITHDRAWAL_APPROVED", "WithdrawalRequest",
                String.valueOf(withdrawalId));

        return request;
    }

    /**
     * Remise en especes : envoie au joueur, present au guichet, le code qui
     * autorise l'agent a lui remettre l'argent. Le SMS annonce le montant net.
     */
    @Transactional
    public void sendPickupCode(Long adminId, Long withdrawalId) {
        WithdrawalRequest request = withdrawalRequestRepository.findById(withdrawalId)
                .orElseThrow(() -> ApiException.notFound("Demande de retrait"));

        if (request.getProvider() != PaymentProvider.MANUAL || request.isCounterWithdrawal()) {
            throw new ApiException(ErrorCode.NOT_A_CASH_WITHDRAWAL);
        }
        if (request.getStatus() != WithdrawalStatus.APPROVED) {
            throw new ApiException(ErrorCode.WITHDRAWAL_NOT_APPROVED);
        }

        User user = request.getUser();
        long netHtg = request.getNetHtg();
        otpService.send(user, OtpPurpose.CASH_PICKUP, user.getPhone(),
                code -> "RAF : code " + code + " pour recevoir " + netHtg
                        + " HTG en especes au guichet. Ne le donnez qu'a l'agent RAF en face de vous.");

        auditService.record(adminId, "WITHDRAWAL_PICKUP_CODE_SENT", "WithdrawalRequest",
                String.valueOf(withdrawalId));
    }

    /**
     * Versement d'un retrait approuve : etape finale.
     *
     * <p>MonCash / NatCash : purement declaratif, l'agent saisit la reference de
     * l'operateur. Especes : le code recu par le joueur est exige, sinon
     * n'importe qui se presentant sous son nom repartirait avec ses gains.
     *
     * <p>Verrou sur la demande : deux validations simultanees ne peuvent pas
     * consommer le code et marquer la remise deux fois.
     */
    @Transactional
    public WithdrawalRequest markPaid(Long adminId, Long withdrawalId, String payoutReference,
            String pickupCode) {
        WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> ApiException.notFound("Demande de retrait"));

        if (request.getStatus() != WithdrawalStatus.APPROVED) {
            throw new ApiException(ErrorCode.WITHDRAWAL_NOT_APPROVED);
        }

        if (request.getProvider() == PaymentProvider.MANUAL) {
            if (pickupCode == null || pickupCode.isBlank()) {
                throw new ApiException(ErrorCode.PICKUP_CODE_REQUIRED);
            }
            otpService.verify(request.getUser(), OtpPurpose.CASH_PICKUP, pickupCode);
        } else if (payoutReference == null || payoutReference.isBlank()) {
            throw new ApiException(ErrorCode.PAYOUT_REFERENCE_REQUIRED);
        }

        request.setStatus(WithdrawalStatus.PAID);
        request.setPayoutReference(payoutReference);
        request.setPaidAt(Instant.now());

        auditService.record(adminId, "WITHDRAWAL_PAID", "WithdrawalRequest",
                String.valueOf(withdrawalId), null,
                request.getProvider() == PaymentProvider.MANUAL
                        ? "especes remises au guichet" + (payoutReference == null ? "" : ", recu " + payoutReference)
                        : payoutReference);

        return request;
    }

    /** Rejet : les gains geles retournent au joueur. */
    @Transactional
    public WithdrawalRequest reject(Long adminId, Long withdrawalId, String reason) {
        WithdrawalRequest request = loadPending(withdrawalId);

        WalletTransaction refund = walletService.credit(
                request.getUser().getId(),
                BalanceType.WINNINGS,
                request.getAmountCredits(),
                WalletTransactionType.REFUND,
                "WithdrawalRequest",
                String.valueOf(withdrawalId),
                "withdrawal-refund:" + withdrawalId,
                "Retrait refuse : " + reason);

        request.setStatus(WithdrawalStatus.REJECTED);
        request.setRejectionReason(reason);
        request.setReviewedByAdminId(adminId);
        request.setReviewedAt(Instant.now());
        request.setRefundTransactionId(refund.getId());

        auditService.record(adminId, "WITHDRAWAL_REJECTED", "WithdrawalRequest",
                String.valueOf(withdrawalId), null, reason);

        return request;
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalRequest> historyForUser(Long userId, Pageable pageable) {
        return withdrawalRequestRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalRequest> byStatus(WithdrawalStatus status, Pageable pageable) {
        return withdrawalRequestRepository.findByStatusOrderByCreatedAtAsc(status, pageable);
    }

    private WithdrawalRequest loadPending(Long withdrawalId) {
        WithdrawalRequest request = withdrawalRequestRepository.findById(withdrawalId)
                .orElseThrow(() -> ApiException.notFound("Demande de retrait"));

        if (request.getStatus() != WithdrawalStatus.PENDING) {
            throw new ApiException(ErrorCode.WITHDRAWAL_NOT_PENDING);
        }
        return request;
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
    }

    private void assertPhoneVerified(User user) {
        if (!user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_NOT_VERIFIED,
                    "Verifiez votre numero de telephone avant de demander un retrait.");
        }
    }
}
