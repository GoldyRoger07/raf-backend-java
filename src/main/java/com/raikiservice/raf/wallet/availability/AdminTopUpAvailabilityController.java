package com.raikiservice.raf.wallet.availability;

import com.raikiservice.raf.auth.security.UserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interrupteur « Disponible pour les recharges » du back-office. Reserve aux
 * administrateurs par la regle {@code /api/admin/**} : ce sont eux qui creditent.
 */
@RestController
@RequestMapping("/api/admin/topup-availability")
@RequiredArgsConstructor
@Tag(name = "Administration - Finances")
public class AdminTopUpAvailabilityController {

    private final TopUpAvailabilityService availabilityService;

    /** {@code online} : l'agent qui appelle ; {@code available} : au moins un agent. */
    public record AgentStatus(boolean online, boolean available) {
    }

    @GetMapping
    @Operation(summary = "Etat de l'agent connecte et disponibilite globale des recharges")
    public AgentStatus status(@AuthenticationPrincipal UserPrincipal admin) {
        return statusOf(admin);
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "Se declarer disponible (a renouveler toutes les 30 s)")
    public AgentStatus heartbeat(@AuthenticationPrincipal UserPrincipal admin) {
        availabilityService.heartbeat(admin.getId());
        return statusOf(admin);
    }

    @DeleteMapping("/heartbeat")
    @Operation(summary = "Ne plus etre disponible pour les recharges")
    public AgentStatus offline(@AuthenticationPrincipal UserPrincipal admin) {
        availabilityService.goOffline(admin.getId());
        return statusOf(admin);
    }

    private AgentStatus statusOf(UserPrincipal admin) {
        return new AgentStatus(availabilityService.isOnline(admin.getId()),
                availabilityService.availability().available());
    }
}
