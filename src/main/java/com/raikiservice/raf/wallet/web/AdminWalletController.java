package com.raikiservice.raf.wallet.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.wallet.domain.TopUpStatus;
import com.raikiservice.raf.wallet.domain.WithdrawalStatus;
import com.raikiservice.raf.wallet.service.AdjustmentService;
import com.raikiservice.raf.wallet.service.CounterWithdrawalService;
import com.raikiservice.raf.wallet.service.TopUpService;
import com.raikiservice.raf.wallet.service.WalletService;
import com.raikiservice.raf.wallet.service.WithdrawalService;
import com.raikiservice.raf.wallet.web.dto.AdjustmentDto;
import com.raikiservice.raf.wallet.web.dto.CounterWithdrawalConfirmDto;
import com.raikiservice.raf.wallet.web.dto.CounterWithdrawalDto;
import com.raikiservice.raf.wallet.web.dto.ManualTopUpDto;
import com.raikiservice.raf.wallet.web.dto.PayoutDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final AdjustmentService adjustmentService;
    private final CounterWithdrawalService counterWithdrawalService;

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
    @Operation(summary = "Lister les demandes de retrait par statut")
    public Page<WithdrawalResponse> withdrawals(
            @RequestParam(defaultValue = "PENDING") WithdrawalStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return withdrawalService.byStatus(status, pageable).map(WithdrawalResponse::from);
    }

    @PostMapping("/withdrawals/{id}/approve")
    @Operation(summary = "Approuver une demande de retrait")
    public WithdrawalResponse approve(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        return WithdrawalResponse.from(withdrawalService.approve(admin.getId(), id));
    }

    @PostMapping("/withdrawals/{id}/reject")
    @Operation(summary = "Refuser une demande et rendre les gains au joueur")
    public WithdrawalResponse reject(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody RejectionDto request) {
        return WithdrawalResponse.from(withdrawalService.reject(admin.getId(), id, request.reason()));
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
                request.amountCredits(), request.reason()));
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Consulter le portefeuille d'un joueur")
    public WalletResponse userWallet(@PathVariable Long userId) {
        walletService.getOrCreate(userId);
        return WalletResponse.from(walletService.requireWallet(userId), walletService.htgRate());
    }

    @GetMapping("/users/{userId}/transactions")
    @Operation(summary = "Consulter l'historique d'un joueur")
    public Page<TransactionResponse> userTransactions(@PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        walletService.getOrCreate(userId);
        return walletService.history(userId, pageable).map(TransactionResponse::from);
    }
}
