package com.raikiservice.raf.common.settings;

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

    @GetMapping("/social")
    @Operation(summary = "Liens vers les reseaux sociaux actives par l'administrateur")
    public SocialService.PublicLinks social() {
        return socialService.publicLinks();
    }
}
