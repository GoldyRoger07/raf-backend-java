package com.raikiservice.raf.wallet.service;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.OtpService;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.repository.WithdrawalRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrait en especes au guichet, saisi par un agent pour un joueur present.
 *
 * <p>Deux temps, pour que le code autorise un montant precis :
 * <ol>
 *   <li>{@link #initiate} : l'agent saisit le montant. Les controles sont faits,
 *       la demande est creee en AWAITING_OTP <em>sans rien debiter</em>, et le
 *       joueur recoit par SMS un code qui annonce le montant ;
 *   <li>{@link #confirm} : le joueur donne le code a l'agent, qui le saisit. Les
 *       gains sont debites et la demande passe directement en PAID : les especes
 *       sont remises sur place, il n'y a pas d'etape de versement.
 * </ol>
 *
 * <p>Une nouvelle saisie annule la precedente encore en attente. Comme un nouvel
 * OTP invalide aussi le precedent, le seul code valable correspond toujours a la
 * seule demande confirmable, donc au montant annonce dans le dernier SMS.
 *
 * <p>Sans le code, un compte agent ne peut pas retirer les gains d'un joueur
 * absent : c'est tout l'objet de ce flux.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterWithdrawalService {

    private static final long DEFAULT_COUNTER_FEE_HTG = 25L;

    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final WithdrawalService withdrawalService;
    private final OtpService otpService;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public WithdrawalRequest initiate(Long adminId, Long userId, long amountHtg, String note) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));

        // Le code part sur le telephone verifie : c'est lui qui authentifie le joueur.
        if (!user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_NOT_VERIFIED,
                    "Le joueur doit d'abord verifier son numero de telephone.");
        }

        withdrawalService.assertWithinLimits(userId, amountHtg);

        long feeHtg = settingsService.getLong(
                SettingKeys.WITHDRAWAL_COUNTER_FEE_HTG, DEFAULT_COUNTER_FEE_HTG);
        if (feeHtg >= amountHtg) {
            throw new ApiException(ErrorCode.WITHDRAWAL_BELOW_MINIMUM,
                    "Le montant ne couvre pas les frais de " + feeHtg + " HTG.");
        }

        // Controle anticipe, pour ne pas envoyer un code qui ne pourra pas servir.
        // Le debit, a la confirmation, re-verifie le solde sous verrou.
        long available = walletService.getOrCreate(userId).balanceOf(BalanceType.WINNINGS);
        if (available < amountHtg) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Gains disponibles : " + available + " HTG.");
        }

        for (WithdrawalRequest previous : withdrawalRequestRepository
                .findByUserIdAndStatus(userId, WithdrawalStatus.AWAITING_OTP)) {
            previous.setStatus(WithdrawalStatus.CANCELLED);
            previous.setRejectionReason("Remplace par une nouvelle saisie");
            previous.setReviewedByAdminId(adminId);
            previous.setReviewedAt(Instant.now());
        }

        WithdrawalRequest request = new WithdrawalRequest();
        request.setUser(user);
        request.setAmountHtg(amountHtg);
        request.setFeeHtg(feeHtg);
        request.setNetHtg(amountHtg - feeHtg);
        request.setProvider(PaymentProvider.MANUAL);
        request.setStatus(WithdrawalStatus.AWAITING_OTP);
        request.setInitiatedByAdminId(adminId);
        request.setNote(note);
        WithdrawalRequest saved = withdrawalRequestRepository.save(request);

        // En dernier : un echec anterieur ne doit pas laisser un code en circulation.
        long netHtg = saved.getNetHtg();
        otpService.send(user, OtpPurpose.COUNTER_WITHDRAWAL, user.getPhone(),
                code -> "RAF : code " + code + " pour retirer " + netHtg
                        + " HTG en especes au guichet. Ne le donnez qu'a l'agent RAF en face de vous.");

        auditService.record(adminId, "COUNTER_WITHDRAWAL_INITIATED", "WithdrawalRequest",
                String.valueOf(saved.getId()), null,
                "joueur " + userId + ", " + amountHtg + " HTG");

        return saved;
    }

    /**
     * Confirme le retrait avec le code donne par le joueur.
     *
     * <p>Idempotent : une demande deja payee est renvoyee telle quelle, sans
     * nouveau debit (double clic de l'agent, nouvel essai reseau).
     */
    @Transactional
    public WithdrawalRequest confirm(Long adminId, Long withdrawalId, String otpCode,
            String receiptReference) {

        WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> ApiException.notFound("Demande de retrait"));

        if (!request.isCounterWithdrawal()) {
            throw ApiException.notFound("Retrait au guichet");
        }
        if (request.getStatus() == WithdrawalStatus.PAID) {
            return request;
        }
        if (request.getStatus() != WithdrawalStatus.AWAITING_OTP) {
            throw new ApiException(ErrorCode.WITHDRAWAL_NOT_PENDING);
        }

        User user = request.getUser();
        Long userId = user.getId();

        // Re-verifie a la confirmation : d'autres retraits ont pu passer entre-temps.
        withdrawalService.assertWithinLimits(userId, request.getAmountHtg());

        // Verifie juste avant le debit, comme pour le retrait en ligne : un echec
        // anterieur annulerait la consommation du code.
        otpService.verify(user, OtpPurpose.COUNTER_WITHDRAWAL, otpCode);

        Instant now = Instant.now();
        WalletTransaction debit = walletService.debit(
                userId,
                BalanceType.WINNINGS,
                request.getAmountHtg(),
                WalletTransactionType.WITHDRAWAL,
                "WithdrawalRequest",
                String.valueOf(withdrawalId),
                "withdrawal:" + withdrawalId,
                "Retrait au guichet de " + request.getAmountHtg() + " HTG");

        request.setDebitTransactionId(debit.getId());
        request.setOtpVerifiedAt(now);
        request.setStatus(WithdrawalStatus.PAID);
        request.setReviewedByAdminId(adminId);
        request.setReviewedAt(now);
        request.setPaidAt(now);
        request.setPayoutReference(receiptReference);

        notificationService.notifyUser(userId, NotificationMessages.withdrawalPaid(request));

        auditService.record(adminId, "COUNTER_WITHDRAWAL_PAID", "WithdrawalRequest",
                String.valueOf(withdrawalId), null,
                "joueur " + userId + ", " + request.getNetHtg() + " HTG remis"
                        + (receiptReference == null ? "" : ", recu " + receiptReference));
        log.info("Retrait au guichet {} paye : joueur {}, {} HTG nets, agent {}",
                withdrawalId, userId, request.getNetHtg(), adminId);

        return request;
    }

    /** Abandon avant confirmation : rien n'avait ete debite, rien a rendre. */
    @Transactional
    public WithdrawalRequest cancel(Long adminId, Long withdrawalId) {
        WithdrawalRequest request = withdrawalRequestRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> ApiException.notFound("Demande de retrait"));

        if (!request.isCounterWithdrawal()) {
            throw ApiException.notFound("Retrait au guichet");
        }
        if (request.getStatus() != WithdrawalStatus.AWAITING_OTP) {
            throw new ApiException(ErrorCode.WITHDRAWAL_NOT_PENDING);
        }

        request.setStatus(WithdrawalStatus.CANCELLED);
        request.setRejectionReason("Annule par l'agent");
        request.setReviewedByAdminId(adminId);
        request.setReviewedAt(Instant.now());

        auditService.record(adminId, "COUNTER_WITHDRAWAL_CANCELLED", "WithdrawalRequest",
                String.valueOf(withdrawalId));
        return request;
    }
}
