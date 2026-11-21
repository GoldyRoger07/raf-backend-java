package com.raikiservice.raf.wallet.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.wallet.service.WithdrawalCodeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Codes de retrait par email, en secours si les SMS sont indisponibles. Lecture pour
 * tout le personnel du back-office ; bascule reservee au SUPER_ADMIN, tracee au journal
 * d'audit et signalee par email aux administrateurs (WithdrawalCodeService).
 */
@RestController
@RequestMapping("/api/admin/withdrawal-codes")
@RequiredArgsConstructor
@Tag(name = "Administration - Codes de retrait")
public class AdminWithdrawalCodeController {

    private final WithdrawalCodeService withdrawalCodeService;

    public record UpdateDto(@NotNull(message = "Le choix est obligatoire.") Boolean byEmail) {
    }

    @GetMapping
    @Operation(summary = "Les codes de retrait partent-ils par email au lieu du SMS ?")
    public WithdrawalCodeService.Config get() {
        return withdrawalCodeService.config();
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Envoyer les codes de retrait par email (true) ou par SMS (false)")
    public WithdrawalCodeService.Config update(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody UpdateDto request) {
        return withdrawalCodeService.update(admin.getId(), request.byEmail());
    }
}
