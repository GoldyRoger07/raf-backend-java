package com.raikiservice.raf.common.settings;

import com.raikiservice.raf.wallet.availability.TopUpAvailabilityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reglages d'affichage publics, lus sans connexion par l'app joueur et le site
 * vitrine. N'expose que ce que l'administrateur a choisi de montrer.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "Public")
public class PublicConfigController {

    private final SocialService socialService;
    private final DepositNumbersService depositNumbersService;
    private final TopUpAvailabilityService topUpAvailabilityService;
    private final PlayerAppService playerAppService;

    @GetMapping("/social")
    @Operation(summary = "Liens vers les reseaux sociaux actives par l'administrateur")
    public SocialService.PublicLinks social() {
        return socialService.publicLinks();
    }

    @GetMapping("/topup-availability")
    @Operation(summary = "Un agent est-il en ligne pour crediter les recharges en ce moment ?")
    public TopUpAvailabilityService.Availability topUpAvailability() {
        return topUpAvailabilityService.availability();
    }

    @GetMapping("/app-config")
    @Operation(summary = "Comportements de l'app joueur choisis par l'administrateur")
    public PlayerAppService.PlayerAppConfig appConfig() {
        return playerAppService.get();
    }

    @GetMapping("/deposit-numbers")
    @Operation(summary = "Numeros MonCash / NatCash ou envoyer une recharge a distance")
    public DepositNumbersService.PublicNumbers depositNumbers() {
        return depositNumbersService.publicNumbers();
    }
}
