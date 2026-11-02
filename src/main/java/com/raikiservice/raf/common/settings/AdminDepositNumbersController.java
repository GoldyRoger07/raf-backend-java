package com.raikiservice.raf.common.settings;

import com.raikiservice.raf.auth.security.UserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Numeros MonCash / NatCash des recharges a distance (trace au journal d'audit). */
@RestController
@RequestMapping("/api/admin/deposit-numbers")
@RequiredArgsConstructor
@Tag(name = "Administration - Finances")
public class AdminDepositNumbersController {

    private final DepositNumbersService depositNumbersService;

    public record UpdateDepositNumbersDto(
            boolean moncashEnabled,
            @Size(max = 30) String moncashNumber,
            boolean natcashEnabled,
            @Size(max = 30) String natcashNumber) {
    }

    @GetMapping
    @Operation(summary = "Lire les numeros de recharge a distance")
    public DepositNumbersService.Numbers get() {
        return depositNumbersService.get();
    }

    @PutMapping
    @Operation(summary = "Changer, activer ou desactiver les numeros de recharge a distance")
    public DepositNumbersService.Numbers update(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody UpdateDepositNumbersDto request) {
        return depositNumbersService.update(admin.getId(),
                request.moncashEnabled(), request.moncashNumber(),
                request.natcashEnabled(), request.natcashNumber());
    }
}
