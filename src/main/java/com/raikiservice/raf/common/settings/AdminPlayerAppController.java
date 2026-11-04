package com.raikiservice.raf.common.settings;

import com.raikiservice.raf.auth.security.UserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comportements de l'app joueur (administrateurs). Comme le lien Instagram, ils
 * n'engagent pas d'argent : pas reserves au super-administrateur. Chaque
 * modification est tracee au journal d'audit.
 */
@RestController
@RequestMapping("/api/admin/player-app")
@RequiredArgsConstructor
@Tag(name = "Administration - App joueur")
public class AdminPlayerAppController {

    private final PlayerAppService playerAppService;

    @GetMapping
    @Operation(summary = "Lire les comportements de l'app joueur")
    public PlayerAppService.PlayerAppConfig get() {
        return playerAppService.get();
    }

    @PutMapping
    @Operation(summary = "Activer ou couper le rappel de confirmation de l'email (trace au journal d'audit)")
    public PlayerAppService.PlayerAppConfig update(@AuthenticationPrincipal UserPrincipal admin,
            @RequestBody PlayerAppService.PlayerAppConfig request) {
        return playerAppService.update(admin.getId(), request);
    }
}
