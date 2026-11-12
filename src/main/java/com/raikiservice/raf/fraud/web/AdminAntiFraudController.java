package com.raikiservice.raf.fraud.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.fraud.service.AntiFraudService;
import com.raikiservice.raf.fraud.service.AntiFraudService.Overview;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anti-fraude (pas de sans-faute en solo) : interrupteur « tous les joueurs » et liste par
 * joueur. Protege par la regle {@code /api/admin/**} ; chaque changement est trace au
 * journal d'audit.
 */
@RestController
@RequestMapping("/api/admin/anti-fraud")
@RequiredArgsConstructor
@Tag(name = "Administration - Fraude")
public class AdminAntiFraudController {

    private final AntiFraudService antiFraudService;

    @GetMapping
    @Operation(summary = "Anti-fraude : interrupteur pour tous les joueurs et liste des joueurs vises")
    public Overview overview() {
        return antiFraudService.overview();
    }

    @PutMapping("/all-players")
    @Operation(summary = "Activer ou couper l'anti-fraude pour tous les joueurs (staff exclu, parties suivantes)")
    public Overview setAllPlayers(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody AllPlayersDto request) {
        antiFraudService.setAllPlayers(admin.getId(), request.enabled());
        return antiFraudService.overview();
    }

    @PostMapping("/targets")
    @Operation(summary = "Ajouter un joueur a la liste anti-fraude (deja liste : note mise a jour)")
    public Overview addTarget(@AuthenticationPrincipal UserPrincipal admin, @Valid @RequestBody TargetDto request) {
        antiFraudService.addTarget(admin.getId(), request.userId(), request.note());
        return antiFraudService.overview();
    }

    @DeleteMapping("/targets/{userId}")
    @Operation(summary = "Retirer un joueur de la liste : ses prochaines parties sont corrigees normalement")
    public Overview removeTarget(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long userId) {
        antiFraudService.removeTarget(admin.getId(), userId);
        return antiFraudService.overview();
    }

    public record AllPlayersDto(@NotNull Boolean enabled) {
    }

    public record TargetDto(@NotNull Long userId, @NotBlank @Size(max = 255) String note) {
    }
}
