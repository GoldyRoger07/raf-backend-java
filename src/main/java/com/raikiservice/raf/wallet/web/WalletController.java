package com.raikiservice.raf.wallet.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.wallet.service.TopUpRetentionService;
import com.raikiservice.raf.wallet.service.TopUpService;
import com.raikiservice.raf.wallet.service.WalletService;
import com.raikiservice.raf.wallet.service.WithdrawalService;
import com.raikiservice.raf.wallet.web.dto.TopUpResponse;
import com.raikiservice.raf.wallet.web.dto.TransactionResponse;
import com.raikiservice.raf.wallet.web.dto.WalletResponse;
import com.raikiservice.raf.wallet.web.dto.WinningsConversionDto;
import com.raikiservice.raf.wallet.web.dto.WinningsConversionResponse;
import com.raikiservice.raf.wallet.web.dto.WithdrawalRequestDto;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Portefeuille")
public class WalletController {

    private final WalletService walletService;
    private final WithdrawalService withdrawalService;
    private final TopUpService topUpService;
    private final TopUpRetentionService retentionService;

    @GetMapping
    @Operation(summary = "Consulter ses soldes")
    public WalletResponse balance(@AuthenticationPrincipal UserPrincipal principal) {
        walletService.getOrCreate(principal.getId());
        return WalletResponse.from(walletService.requireWallet(principal.getId()),
                walletService.htgRate(), retentionService.balance(principal.getId()));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Historique des mouvements")
    public Page<TransactionResponse> transactions(@AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        walletService.getOrCreate(principal.getId());
        return walletService.history(principal.getId(), pageable).map(TransactionResponse::from);
    }

    @GetMapping("/topups")
    @Operation(summary = "Historique des recharges")
    public Page<TopUpResponse> topups(@AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return topUpService.historyForUser(principal.getId(), pageable).map(TopUpResponse::from);
    }

    @PostMapping("/conversions")
    @Operation(summary = "Recharger ses credits avec ses gains (HTG)")
    public ResponseEntity<WinningsConversionResponse> convertWinnings(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody WinningsConversionDto request) {
        TopUpResponse topUp = TopUpResponse.from(
                topUpService.convertWinnings(principal.getId(), request.amountHtg(), request.requestKey()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WinningsConversionResponse.from(topUp, walletService.requireWallet(principal.getId())));
    }

    @PostMapping("/withdrawals/send-otp")
    @Operation(summary = "Recevoir le code de confirmation d'un retrait par SMS")
    public MessageResponse sendWithdrawalOtp(@AuthenticationPrincipal UserPrincipal principal) {
        withdrawalService.sendWithdrawalOtp(principal.getId());
        return new MessageResponse("Code de confirmation envoye par SMS.");
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Demander un retrait de ses gains")
    public ResponseEntity<WithdrawalResponse> requestWithdrawal(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody WithdrawalRequestDto request) {

        WithdrawalResponse response = WithdrawalResponse.from(withdrawalService.request(
                principal.getId(),
                request.amountHtg(),
                request.provider(),
                request.destinationNumber(),
                request.otpCode()));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/withdrawals")
    @Operation(summary = "Historique de ses demandes de retrait")
    public Page<WithdrawalResponse> withdrawals(@AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return withdrawalService.historyForUser(principal.getId(), pageable)
                .map(WithdrawalResponse::from);
    }
}
