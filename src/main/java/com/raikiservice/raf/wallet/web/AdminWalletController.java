package com.raikiservice.raf.wallet.web;

import java.util.List;
import java.time.LocalDate;

import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.TopUpStatus;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.AdjustmentService;
import com.raikiservice.raf.wallet.service.AdminWithdrawalFilter;
import com.raikiservice.raf.wallet.service.AutoPayoutService;
import com.raikiservice.raf.wallet.service.CounterWithdrawalService;
import com.raikiservice.raf.wallet.service.TopUpRetentionService;
import com.raikiservice.raf.wallet.service.TopUpService;
import com.raikiservice.raf.wallet.service.WalletService;
import com.raikiservice.raf.wallet.service.PayoutSpecialCaseService;
import com.raikiservice.raf.wallet.service.WithdrawalService;
import com.raikiservice.raf.wallet.web.dto.AdjustmentDto;
import com.raikiservice.raf.wallet.web.dto.AdminWithdrawalResponse;
import com.raikiservice.raf.wallet.web.dto.CounterWithdrawalConfirmDto;
import com.raikiservice.raf.wallet.web.dto.CounterWithdrawalDto;
import com.raikiservice.raf.wallet.web.dto.ManualTopUpDto;
import com.raikiservice.raf.wallet.web.dto.PayoutDto;
import com.raikiservice.raf.wallet.web.dto.InstallmentDto;
import com.raikiservice.raf.wallet.web.dto.InstallmentResponse;
import com.raikiservice.raf.wallet.web.dto.RejectionDto;
import com.raikiservice.raf.wallet.web.dto.TopUpResponse;
import com.raikiservice.raf.wallet.web.dto.TransactionResponse;
import com.raikiservice.raf.wallet.web.dto.WalletResponse;
import com.raikiservice.raf.wallet.web.dto.WithdrawalResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Back-office financier.
 *
 * <p>Protege par la regle {@code /api/admin/**} de la configuration de securite.
 * Chaque action laisse une trace nominative dans le journal d'audit.
 */
@RestController
@RequestMapping("/api/admin/wallet")
@RequiredArgsConstructor
@Tag(name = "Administration - Finances")
public class AdminWalletController {

    private final WalletService walletService;
    private final TopUpService topUpService;
    private final WithdrawalService withdrawalService;
    private final AutoPayoutService autoPayoutService;
    private final AdjustmentService adjustmentService;
    private final CounterWithdrawalService counterWithdrawalService;
    private final TopUpRetentionService retentionService;
    private final PayoutSpecialCaseService payoutSpecialCaseService;

    @PostMapping("/topups/manual")
    @Operation(summary = "Crediter un joueur apres un encaissement hors ligne")
    public ResponseEntity<TopUpResponse> manualTopUp(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody ManualTopUpDto request) {

        TopUpResponse response = TopUpResponse.from(topUpService.createManual(
                admin.getId(),
                request.userId(),
                request.amountHtg(),
                request.reference(),
                request.note()));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/topups")
    @Operation(summary = "Lister les recharges par statut")
    public Page<TopUpResponse> topups(
            @RequestParam(defaultValue = "PENDING") TopUpStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return topUpService.byStatus(status, pageable).map(TopUpResponse::from);
    }

    @GetMapping("/withdrawals")
    @Operation(summary = "Demandes de retrait par statut, ou toutes celles d'un joueur (userId)",
            description = "Recherche : nom, email, telephone, numero de destination ou numero de demande. "
                    + "Dates : jours d'Haiti, bornes incluses. Tris : createdAt (par defaut, plus anciennes "
                    + "d'abord), player, netHtg, provider.")
    public Page<AdminWithdrawalResponse> withdrawals(
            @RequestParam(required = false) WithdrawalStatus status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PaymentProvider provider,
            @RequestParam(required = false) Boolean counter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return withdrawalService.adminView(status, userId,
                new AdminWithdrawalFilter(q, provider, counter, from, to), pageable);
    }

    @PostMapping("/withdrawals/{id}/approve")
    @Operation(summary = "Approuver une demande de retrait ; MonCash / NatCash : envoi automatique par Tranzak")
    public WithdrawalResponse approve(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        return WithdrawalResponse.from(autoPayoutService.approve(admin.getId(), id));
    }

    @PostMapping("/withdrawals/{id}/send")
    @Operation(summary = "Relancer l'envoi automatique d'un retrait approuve (apres un echec)")
    public WithdrawalResponse sendPayout(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        return WithdrawalResponse.from(autoPayoutService.send(admin.getId(), id));
    }

    @GetMapping("/payouts/config")
    @Operation(summary = "Envoi automatique des retraits : actif, et frais en points de base")
    public AutoPayoutService.Config payoutConfig() {
        return autoPayoutService.config();
    }

    @PostMapping("/withdrawals/{id}/reject")
    @Operation(summary = "Refuser une demande et rendre les gains au joueur")
    public WithdrawalResponse reject(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody RejectionDto request) {
        return WithdrawalResponse.from(withdrawalService.reject(admin.getId(), id, request.reason()));
    }

    @PostMapping("/withdrawals/{id}/installments")
    @Operation(summary = "Verser une tranche d'un retrait MonCash / NatCash approuve (versement manuel)",
            description = "Montant net de la tranche et reference de l'operateur. Le retrait passe en "
                    + "PARTIALLY_PAID, puis en PAID quand tout le net est verse.")
    public WithdrawalResponse payInstallment(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody InstallmentDto request) {
        return WithdrawalResponse.from(withdrawalService.payInstallment(
                admin.getId(), id, request.amountHtg(), request.payoutReference()));
    }

    @GetMapping("/withdrawals/{id}/installments")
    @Operation(summary = "Tranches deja versees d'un retrait, la plus ancienne d'abord")
    public List<InstallmentResponse> installments(@PathVariable Long id) {
        return withdrawalService.installments(id).stream().map(InstallmentResponse::from).toList();
    }

    @PostMapping("/withdrawals/{id}/close")
    @Operation(summary = "Clore un retrait verse en partie et rendre le reste aux gains du joueur")
    public WithdrawalResponse closeWithRefund(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody RejectionDto request) {
        return WithdrawalResponse.from(withdrawalService.closeWithRefund(admin.getId(), id, request.reason()));
    }

    @GetMapping("/payout-special-cases")
    @Operation(summary = "Cas de retrait particuliers : joueurs payables par tranches, qui doivent accepter avant le solo")
    public List<PayoutSpecialCaseService.SpecialCase> specialCases() {
        return payoutSpecialCaseService.list();
    }

    @PostMapping("/payout-special-cases")
    @Operation(summary = "Ajouter un joueur aux cas de retrait particuliers (deja present : note mise a jour)")
    public List<PayoutSpecialCaseService.SpecialCase> addSpecialCase(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody SpecialCaseDto request) {
        payoutSpecialCaseService.add(admin.getId(), request.userId(), request.note());
        return payoutSpecialCaseService.list();
    }

    @DeleteMapping("/payout-special-cases/{userId}")
    @Operation(summary = "Retirer un joueur des cas de retrait particuliers (son accord est garde)")
    public List<PayoutSpecialCaseService.SpecialCase> removeSpecialCase(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long userId) {
        payoutSpecialCaseService.remove(admin.getId(), userId);
        return payoutSpecialCaseService.list();
    }

    public record SpecialCaseDto(@jakarta.validation.constraints.NotNull Long userId,
            @jakarta.validation.constraints.Size(max = 255) String note) {
    }

    @PostMapping("/withdrawals/{id}/pickup-code")
    @Operation(summary = "Retrait en especes : envoyer au joueur present le code de remise")
    public MessageResponse sendPickupCode(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        withdrawalService.sendPickupCode(admin.getId(), id);
        return new MessageResponse("Code de remise envoye au joueur par SMS.");
    }

    @PostMapping("/withdrawals/{id}/paid")
    @Operation(summary = "Marquer le versement comme effectue (especes : code du joueur exige)")
    public WithdrawalResponse markPaid(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody PayoutDto request) {
        return WithdrawalResponse.from(withdrawalService.markPaid(
                admin.getId(), id, request.payoutReference(), request.pickupCode()));
    }

    @PostMapping("/users/{userId}/counter-withdrawals")
    @Operation(summary = "Saisir un retrait au guichet : envoie un code au joueur, rien n'est debite")
    public ResponseEntity<WithdrawalResponse> initiateCounterWithdrawal(
            @AuthenticationPrincipal UserPrincipal admin, @PathVariable Long userId,
            @Valid @RequestBody CounterWithdrawalDto request) {

        WithdrawalResponse response = WithdrawalResponse.from(counterWithdrawalService.initiate(
                admin.getId(), userId, request.amountHtg(), request.note()));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/counter-withdrawals/{id}/confirm")
    @Operation(summary = "Confirmer un retrait au guichet avec le code du joueur : debit et remise des especes")
    public WithdrawalResponse confirmCounterWithdrawal(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody CounterWithdrawalConfirmDto request) {
        return WithdrawalResponse.from(counterWithdrawalService.confirm(
                admin.getId(), id, request.otpCode(), request.receiptReference()));
    }

    @PostMapping("/counter-withdrawals/{id}/cancel")
    @Operation(summary = "Abandonner un retrait au guichet non confirme")
    public WithdrawalResponse cancelCounterWithdrawal(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        return WithdrawalResponse.from(counterWithdrawalService.cancel(admin.getId(), id));
    }

    @PostMapping("/users/{userId}/adjust")
    @Operation(summary = "Corriger manuellement un solde (montant signe, motif obligatoire)")
    public TransactionResponse adjust(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long userId, @Valid @RequestBody AdjustmentDto request) {
        return TransactionResponse.from(adjustmentService.adjust(
                admin.getId(), userId, request.balanceType(),
                request.amount(), request.reason()));
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Consulter le portefeuille d'un joueur")
    public WalletResponse userWallet(@PathVariable Long userId) {
        walletService.getOrCreate(userId);
        return WalletResponse.from(walletService.requireWallet(userId), walletService.htgRate(),
                retentionService.balance(userId));
    }

    @GetMapping("/users/{userId}/retention")
    @Operation(summary = "Retenue de recharge d'un joueur : solde et derniers mouvements")
    public TopUpRetentionService.View userRetention(@PathVariable Long userId) {
        return retentionService.view(userId, 50);
    }

    @PostMapping("/users/{userId}/retention/adjust")
    @Operation(summary = "Corriger la retenue de recharge d'un joueur (montant signe en HTG, motif obligatoire)")
    public TopUpRetentionService.View adjustRetention(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long userId, @Valid @RequestBody RetentionAdjustmentDto request) {
        retentionService.adjust(admin.getId(), userId, request.amountHtg(), request.reason());
        return retentionService.view(userId, 50);
    }

    /** Correction de la retenue : montant signe en gourdes et motif. */
    public record RetentionAdjustmentDto(
            @jakarta.validation.constraints.NotNull Long amountHtg,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String reason) {
    }

    @GetMapping("/users/{userId}/transactions")
    @Operation(summary = "Consulter l'historique d'un joueur")
    public Page<TransactionResponse> userTransactions(@PathVariable Long userId,
            @RequestParam(required = false) WalletTransactionType type,
            @RequestParam(required = false) BalanceType balanceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        walletService.getOrCreate(userId);
        return walletService.adminHistory(userId, type, balanceType, from, to, q, pageable)
                .map(TransactionResponse::from);
    }
}
